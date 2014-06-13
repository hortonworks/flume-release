/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flume.sink.hive;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.hive.hcatalog.streaming.*;

import org.apache.flume.Event;

import org.apache.flume.instrumentation.SinkCounter;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Internal API intended for HiveSink use.
 */
class HiveWriter {

  private static final Logger LOG = LoggerFactory
      .getLogger(HiveWriter.class);

  private final HiveEndPoint endPoint;
  private HiveEventSerializer serializer;
  private final StreamingConnection connection;
  private final int txnsPerBatch;
  private final RecordWriter recordWriter;
  private TransactionBatch txnBatch;

  private final ExecutorService callTimeoutPool;

  private final long callTimeout;

  private long lastUsed; // time of last flush on this writer

  private SinkCounter sinkCounter;
  private int batchCounter;
  private long eventCounter;
  private long processSize;

  protected boolean closed; // flag indicating HiveWriter was closed
  private boolean autoCreatePartitions;

  private boolean hearbeatNeeded = false;

  HiveWriter(HiveEndPoint endPoint, int txnsPerBatch,
             boolean autoCreatePartitions, long callTimeout,
             ExecutorService callTimeoutPool, String hiveUser,
             HiveEventSerializer serializer, SinkCounter sinkCounter)
          throws ConnectFailure, InterruptedException {
    try {
      this.autoCreatePartitions = autoCreatePartitions;
      this.sinkCounter = sinkCounter;
      this.callTimeout = callTimeout;
      this.callTimeoutPool = callTimeoutPool;
      this.endPoint = endPoint;
      this.connection = newConnection(hiveUser);
      this.txnsPerBatch = txnsPerBatch;
      this.serializer = serializer;
      this.recordWriter = serializer.createRecordWriter(endPoint);
      this.txnBatch = nextTxnBatch(recordWriter);
      this.closed = false;
      this.lastUsed = System.currentTimeMillis();
    } catch (InterruptedException e) {
      throw e;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new ConnectFailure(endPoint, e);
    }
  }

  @Override
  public String toString() {
    return endPoint.toString();
  }

  /**
   * Clear the class counters
   */
  private void resetCounters() {
    eventCounter = 0;
    processSize = 0;
    batchCounter = 0;
  }

  void setHearbeatNeeded() {
    hearbeatNeeded = true;
  }


  /**
   * Write data, update stats
   * @param event
   * @throws StreamingException
   * @throws InterruptedException
   */
  public synchronized void write(final Event event)
          throws WriteFailure, InterruptedException {
    if (closed) {
      throw new IllegalStateException("Writer closed. Cannot write to : " + endPoint);
    }

    // write the event
    sinkCounter.incrementEventDrainAttemptCount();
    try {
      timedCall(new CallRunner1<Void>() {
        @Override
        public Void call() throws InterruptedException, StreamingException {
          try {
            serializer.write(txnBatch, event);
            return null;
          } catch (IOException e) {
            throw new StreamingIOFailure(e.getMessage(), e);
          }
        }
      });
    } catch (StreamingException e) {
      throw new WriteFailure(endPoint, txnBatch.getCurrentTxnId(), e);
    } catch (TimeoutException e) {
      throw new WriteFailure(endPoint, txnBatch.getCurrentTxnId(), e);
    }

    // Update Statistics
    processSize += event.getBody().length;
    eventCounter++;
  }

  /**
   * Commits the current Txn.
   * If 'rollToNext' is true, will switch to next Txn in batch or to a
   *       new TxnBatch if current Txn batch is exhausted
   */
  public void flush(boolean rollToNext)
          throws CommitFailure, InterruptedException {
    //0 Heart beat on TxnBatch
    if(hearbeatNeeded) {
      hearbeatNeeded = false;
      heartBeat();
    }
    lastUsed = System.currentTimeMillis();

    try {
      //1 commit txn & close batch if needed
      commitTxn();
      if(txnBatch.remainingTransactions() == 0) {
        closeTxnBatch();
        txnBatch = null;
        if(rollToNext) {
          txnBatch = nextTxnBatch(recordWriter);
        }
      }

      //2 roll to next Txn
      if(rollToNext) {
        LOG.debug("Switching to next Txn for {}", endPoint);
        txnBatch.beginNextTransaction(); // does not block
      }
    } catch (IOException e) {
      throw new CommitFailure(endPoint, txnBatch.getCurrentTxnId(), e);
    } catch (StreamingException e) {
      throw new CommitFailure(endPoint, txnBatch.getCurrentTxnId(), e);
    }
  }

  /**
   * Aborts the current Txn and switches to next Txn.
   * @throws StreamingException if could not get new Transaction Batch, or switch to next Txn
   */
  public void abort()  throws InterruptedException {
    abortTxn();
  }

  /** Queues up a heartbeat request on the current and remaining txns using the
   *  heartbeatThdPool and returns immediately
   */
  public void heartBeat() throws InterruptedException  {
    // 1) schedule the heartbeat on one thread in pool
    try {
      timedCall(new CallRunner1<Void>() {
        @Override
        public Void call() throws StreamingException {
          LOG.info("Sending heartbeat on batch " + txnBatch);
          txnBatch.heartbeat();
          return null;
        }
      });
    } catch (InterruptedException e) {
      throw e;
    } catch (Exception e) {
      LOG.warn("Unable to send heartbeat on Txn Batch " + txnBatch, e);
      // Suppressing exceptions as we don't care for errors on heartbeats
    }
  }

  /**
   * Close the Transaction Batch and connection
   * @throws IOException
   * @throws InterruptedException
   */
  public void close() throws InterruptedException {
    closeTxnBatch();
    closeConnection();
    closed = true;
  }

  private void closeConnection() throws InterruptedException {
    LOG.info("Closing connection to EndPoint : {}", endPoint);
    try {
      timedCall(new CallRunner1<Void>() {
        @Override
        public Void call() {
          connection.close(); // could block
          return null;
        }
      });
      sinkCounter.incrementConnectionClosedCount();
    } catch (Exception e) {
      LOG.warn("Error closing connection to EndPoint : " + endPoint, e);
      // Suppressing exceptions as we don't care for errors on connection close
    }
  }

  private void commitTxn() throws CommitFailure, InterruptedException {
    if (LOG.isInfoEnabled()) {
      LOG.info("Committing Txn " + txnBatch.getCurrentTxnId() + " on EndPoint: " + endPoint);
    }
    try {
      timedCall(new CallRunner1<Void>() {
        @Override
        public Void call() throws StreamingException, InterruptedException {
          txnBatch.commit(); // could block
          return null;
        }
      });
    } catch (StreamingException e) {
      throw new CommitFailure(endPoint, txnBatch.getCurrentTxnId(), e);
    } catch (TimeoutException e) {
      throw new CommitFailure(endPoint, txnBatch.getCurrentTxnId(), e);
    }
  }

  private void abortTxn() throws InterruptedException {
    LOG.info("Aborting Txn id {} on End Point {}", txnBatch.getCurrentTxnId(), endPoint);
    try {
      timedCall(new CallRunner1<Void>() {
        @Override
        public Void call() throws StreamingException, InterruptedException {
          txnBatch.abort(); // could block
          return null;
        }
      });
    } catch (InterruptedException e) {
      throw e;
    } catch (TimeoutException e) {
      LOG.warn("Timeout while aborting Txn " + txnBatch.getCurrentTxnId() + " on EndPoint: " + endPoint, e);
    } catch (StreamingException e) {
      LOG.warn("Error aborting Txn " + txnBatch.getCurrentTxnId() + " on EndPoint: " + endPoint, e);
      // Suppressing exceptions as we don't care for errors on abort
    }
  }

  private StreamingConnection newConnection(final String proxyUser)
          throws InterruptedException, ConnectFailure {
    try {
      return  timedCall(new CallRunner1<StreamingConnection>() {
        @Override
        public StreamingConnection call() throws InterruptedException, StreamingException {
          return endPoint.newConnection(autoCreatePartitions); // could block
        }
      });
    } catch (StreamingException e) {
      throw new ConnectFailure(endPoint, e);
    } catch (TimeoutException e) {
      throw new ConnectFailure(endPoint, e);
    }
  }

  private TransactionBatch nextTxnBatch(final RecordWriter recordWriter)
          throws IOException, InterruptedException, StreamingException {
    LOG.debug("Fetching new Txn Batch for {}", endPoint);
    TransactionBatch batch = callWithTimeout(new CallRunner<TransactionBatch>() {
              @Override
              public TransactionBatch call() throws Exception {
                return connection.fetchTransactionBatch(txnsPerBatch, recordWriter); // could block
              }
            });
    LOG.info("Acquired Txn Batch {}. Switching to first txn", batch);
    batch.beginNextTransaction();
    return batch;
  }


  private void closeTxnBatch() throws InterruptedException {
    try {
      LOG.debug("Closing Txn Batch {}", txnBatch);
      timedCall(new CallRunner1<Void>() {
        @Override
        public Void call() throws InterruptedException, StreamingException {
          txnBatch.close(); // could block
          return null;
        }
      });
    } catch (InterruptedException e) {
      throw e;
    } catch (Exception e) {
      LOG.warn("Error closing Txn Batch " + txnBatch, e);
      // Suppressing exceptions as we don't care for errors on batch close
    }
  }

//  /**
//   * If the current thread has been interrupted, then throws an
//   * exception.
//   * @throws InterruptedException
//   */
//  private static void checkAndThrowInterruptedException()
//          throws InterruptedException {
//    if (Thread.currentThread().interrupted()) {
//      throw new InterruptedException("Timed out before Hive call was made. "
//              + "Your callTimeout might be set too low or Hive calls are "
//              + "taking too long.");
//    }
//  }
//



//  private <T> T timedCall(final CallRunner1<T> callRunner)
//          throws TimeoutException, InterruptedException, ExecutionException {
//    Future<T> future = callTimeoutPool.submit(new Callable<T>() {
//      @Override
//      public T call() throws StreamingException, InterruptedException, TimeoutException {
//        return callRunner.call();
//      }
//    });
//
//    try {
//      if (callTimeout > 0) {
//        return future.get(callTimeout, TimeUnit.MILLISECONDS);
//      } else {
//        return future.get();
//      }
//    } catch (TimeoutException eT) {
//      future.cancel(true);
//      sinkCounter.incrementConnectionFailedCount();
//      throw eT;
//    } catch (ExecutionException e1) {
//      sinkCounter.incrementConnectionFailedCount();
//      Throwable cause = e1.getCause();
//      if (cause instanceof RuntimeException) {
//        throw (RuntimeException) cause;
//      } else if (cause instanceof InterruptedException) {
//        throw (InterruptedException) cause;
//      } else if (cause instanceof Error) {
//        throw (Error)cause;
//      } else {
//        throw e1;
//      }
//    }
//  }

  private <T> T timedCall(final CallRunner1<T> callRunner)
          throws TimeoutException, InterruptedException, StreamingException {
    Future<T> future = callTimeoutPool.submit(new Callable<T>() {
      @Override
      public T call() throws StreamingException, InterruptedException {
        return callRunner.call();
      }
    });

    try {
      if (callTimeout > 0) {
        return future.get(callTimeout, TimeUnit.MILLISECONDS);
      } else {
        return future.get();
      }
    } catch (TimeoutException eT) {
      future.cancel(true);
      sinkCounter.incrementConnectionFailedCount();
      throw eT;
    } catch (ExecutionException e1) {
      sinkCounter.incrementConnectionFailedCount();
      Throwable cause = e1.getCause();
      if (cause instanceof IOException ) {
        throw new StreamingIOFailure("I/O Failure", (IOException) cause);
      } else if (cause instanceof StreamingException) {
        throw (StreamingException) cause;
      } else if (cause instanceof TimeoutException) {
        throw new StreamingException("Operation Timed Out.", (TimeoutException) cause);
      } else if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      } else if (cause instanceof InterruptedException) {
        throw (InterruptedException) cause;
      }
      throw new RuntimeException(e1);
    }
  }

  /**
   * Execute the callable on a separate thread and wait for the completion
   * for the specified amount of time in milliseconds. In case of timeout
   * cancel the callable and throw an IOException
   */
  private <T> T callWithTimeout(final CallRunner<T> callRunner)
    throws IOException, InterruptedException, StreamingException {
    Future<T> future = callTimeoutPool.submit(new Callable<T>() {
      @Override
      public T call() throws Exception {
//        return runPrivileged(new PrivilegedExceptionAction<T>() {
//          @Override
//          public T run() throws Exception {
            return callRunner.call();
//          }
//        });
      }
    });
    try {
      if (callTimeout > 0) {
        return future.get(callTimeout, TimeUnit.MILLISECONDS);
      } else {
        return future.get();
      }
    } catch (TimeoutException eT) {
      future.cancel(true);
      sinkCounter.incrementConnectionFailedCount();
      throw new IOException("Callable timed out after " + callTimeout + " ms" +
          " on EndPoint: " + endPoint,
        eT);
    } catch (ExecutionException e1) {
      sinkCounter.incrementConnectionFailedCount();
      Throwable cause = e1.getCause();
      if (cause instanceof IOException) {
        throw (IOException) cause;
      } else if (cause instanceof InterruptedException) {
        throw (InterruptedException) cause;
      } else if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      } else if (cause instanceof Error) {
        throw (Error)cause;
      } else {
        throw new RuntimeException(e1);
      }
    } catch (CancellationException ce) {
      throw new InterruptedException(
        "Blocked callable interrupted by rotation event");
    }
  }

  long getLastUsed() {
    return lastUsed;
  }

  /**
   * Simple interface whose <tt>call</tt> method is called by
   * {#callWithTimeout} in a new thread inside a
   * {@linkplain java.security.PrivilegedExceptionAction#run()} call.
   * @param <T>
   */
  private interface CallRunner<T> {
    T call() throws Exception;
  }

//  private interface CallRunner0<T> {
//    T call();
//  }

  private interface CallRunner1<T> {
    T call() throws StreamingException, InterruptedException;
  }

  private interface CallRunner2<X extends Exception> {
    void call() throws StreamingException, InterruptedException, X;
  }


  public static class Failure extends Exception {
    public Failure(String msg, Throwable cause) {
      super(msg, cause);
    }
  }

  public static class WriteFailure extends Failure {
    public WriteFailure(HiveEndPoint endPoint, Long currentTxnId, Throwable cause) {
      super("Failed writing to : " + endPoint + ". TxnID : " + currentTxnId, cause);
    }
  }

  public static class CommitFailure extends Failure {
    public CommitFailure(HiveEndPoint endPoint, Long txnID, Throwable cause) {
      super("Commit of Txn " + txnID +  " failed on EndPoint: " + endPoint, cause);
    }
  }

  public static class ConnectFailure extends Failure {
    public ConnectFailure(HiveEndPoint ep, Throwable cause) {
      super("Failed connecting to EndPoint " + ep, cause);
    }
  }

}
