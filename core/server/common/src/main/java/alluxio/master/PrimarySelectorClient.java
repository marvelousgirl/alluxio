/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master;

import alluxio.AlluxioURI;
import alluxio.Constants;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListener;
import org.apache.curator.framework.recipes.leader.Participant;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Masters use this client to elect a leader.
 */
@NotThreadSafe
public final class PrimarySelectorClient
    implements Closeable, LeaderSelectorListener, PrimarySelector {
  private static final Logger LOG = LoggerFactory.getLogger(PrimarySelectorClient.class);

  /** The election path in Zookeeper. */
  private final String mElectionPath;
  /** The path of the leader in Zookeeper. */
  private final String mLeaderFolder;
  /** The PrimarySelector used to elect. */
  private final LeaderSelector mLeaderSelector;
  /** The name of this master in Zookeeper. */
  private String mName;
  /** The address to Zookeeper. */
  private final String mZookeeperAddress;

  private final Lock mStateLock = new ReentrantLock();
  private final Condition mStateCondition = mStateLock.newCondition();
  /**
   * Whether this master is the primary master now. Whenever this changes, {@link #mStateCondition}
   * must be signalled. To enforce this, all modification should go through
   * {@link #setState(State)}.
   */
  @GuardedBy("mStateLock")
  private State mState = State.SECONDARY;

  /**
   * Constructs a new {@link PrimarySelectorClient}.
   *
   * @param zookeeperAddress the address to Zookeeper
   * @param electionPath the election path
   * @param leaderPath the path of the leader
   */
  public PrimarySelectorClient(String zookeeperAddress, String electionPath, String leaderPath) {
    mZookeeperAddress = zookeeperAddress;
    mElectionPath = electionPath;
    if (leaderPath.endsWith(AlluxioURI.SEPARATOR)) {
      mLeaderFolder = leaderPath;
    } else {
      mLeaderFolder = leaderPath + AlluxioURI.SEPARATOR;
    }

    // Create a leader selector using the given path for management.
    // All participants in a given leader selection must use the same path.
    mLeaderSelector = new LeaderSelector(getNewCuratorClient(), mElectionPath, this);

    // For most cases you will want your instance to requeue when it relinquishes leadership.
    mLeaderSelector.autoRequeue();
  }

  @Override
  public void close() throws IOException {
    try {
      mLeaderSelector.close();
    } catch (IllegalStateException e) {
      // TODO(hy): This should not happen in unit tests.
      if (!e.getMessage().equals("Already closed or has not been started")) {
        throw e;
      }
    }
  }

  @Override
  public void stop() throws IOException {
    close();
  }

  /**
   * @return the leader name
   */
  public String getName() {
    return mName;
  }

  /**
   * @return the list of participants
   */
  public List<String> getParticipants() {
    try {
      List<Participant> participants = new ArrayList<>(mLeaderSelector.getParticipants());
      List<String> results = new ArrayList<>();
      for (Participant part : participants) {
        results.add(part.getId());
      }
      return results;
    } catch (Exception e) {
      LOG.error(e.getMessage(), e);
      return null;
    }
  }

  @Override
  public void waitForState(State state) throws InterruptedException {
    mStateLock.lock();
    try {
      while (mState != state) {
        mStateCondition.await();
      }
    } finally {
      mStateLock.unlock();
    }
  }

  /**
   * Starts the leader selection. If the leader selector client loses connection to Zookeeper or
   * gets closed, the calling thread will be interrupted.
   */
  @Override
  public void start(InetSocketAddress address) throws IOException {
    mName = address.getHostName() + ":" + address.getPort();
    mLeaderSelector.setId(mName);
    mLeaderSelector.start();
  }

  @Override
  public void stateChanged(CuratorFramework client, ConnectionState newState) {
    setState(State.SECONDARY);

    if ((newState != ConnectionState.LOST) && (newState != ConnectionState.SUSPENDED)) {
      try {
        String leaderId = mLeaderSelector.getLeader().getId();
        if (!leaderId.isEmpty()) {
          LOG.info("The current leader is {}", leaderId);
        }
      } catch (Exception e) {
        LOG.error(e.getMessage(), e);
      }
    }
  }

  @Override
  public void takeLeadership(CuratorFramework client) throws Exception {
    setState(State.PRIMARY);
    if (client.checkExists().forPath(mLeaderFolder + mName) != null) {
      LOG.info("Deleting zk path: {}{}", mLeaderFolder, mName);
      client.delete().forPath(mLeaderFolder + mName);
    }
    LOG.info("Creating zk path: {}{}", mLeaderFolder, mName);
    client.create().creatingParentsIfNeeded().forPath(mLeaderFolder + mName);
    LOG.info("{} is now the leader.", mName);
    try {
      mStateLock.lock();
      try {
        while (mState == State.PRIMARY) {
          mStateCondition.await();
        }
      } finally {
        mStateLock.unlock();
      }
    } catch (InterruptedException e) {
      LOG.error(mName + " was interrupted.", e);
      Thread.currentThread().interrupt();
    } finally {
      LOG.warn("{} relinquishing leadership.", mName);
      LOG.info("The current leader is {}", mLeaderSelector.getLeader().getId());
      LOG.info("All participants: {}", mLeaderSelector.getParticipants());
      client.delete().forPath(mLeaderFolder + mName);
    }
  }

  /**
   * Returns a new client for the zookeeper connection. The client is already started before
   * returning.
   *
   * @return a new {@link CuratorFramework} client to use for leader selection
   */
  private CuratorFramework getNewCuratorClient() {
    CuratorFramework client = CuratorFrameworkFactory.newClient(mZookeeperAddress,
        new ExponentialBackoffRetry(Constants.SECOND_MS, 3));
    client.start();

    // Sometimes, if the master crashes and restarts too quickly (faster than the zookeeper
    // timeout), zookeeper thinks the new client is still an old one. In order to ensure a clean
    // state, explicitly close the "old" client and recreate a new one.
    client.close();
    client = CuratorFrameworkFactory.newClient(mZookeeperAddress,
        new ExponentialBackoffRetry(Constants.SECOND_MS, 3));
    client.start();
    return client;
  }

  private void setState(State state) {
    mStateLock.lock();
    try {
      mState = state;
      mStateCondition.signalAll();
    } finally {
      mStateLock.unlock();
    }
  }
}
