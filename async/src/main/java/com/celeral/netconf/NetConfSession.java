/*
 * Copyright © 2020 Celeral.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celeral.netconf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.LinkedList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.tailf.jnc.Capabilities;
import com.tailf.jnc.Element;
import com.tailf.jnc.JNCException;
import com.tailf.jnc.NetconfSession;
import com.tailf.jnc.NodeSet;
import com.tailf.jnc.Transport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.celeral.utils.Closeables;
import com.celeral.utils.Throwables;

public class NetConfSession extends NetconfSession {
  public static final String NETCONF_BASE_1_1_CAPABILITY =
      Capabilities.URN_IETF_PARAMS_NETCONF + "base:1.1";
  private final Charset charset;
  private MessageCodec<ByteBuffer> codec;

  /**
   * Creates a new session object using the given transport object. This will initialize the
   * transport and send out an initial hello message to the server.
   *
   * @see SSHSession
   * @param transport Transport object
   */
  private static class NetConfTransport implements Transport {
    final AtomicBoolean initialized = new AtomicBoolean();
    NetConfSession session;

    private void setNetConfSession(NetConfSession session) {
      if (initialized.get()) {
        throw Throwables.throwFormatted(
            IllegalStateException.class,
            "Attempt to reinitialize already initialized transport {} with session {}!",
            this,
            session);
      } else {
        synchronized (initialized) {
          initialized.set(true);
          this.session = session;
          initialized.notifyAll();
        }
      }
    }

    @Override
    public String readOne(long timeout, TimeUnit timeUnit) throws IOException {
      if (initialized.get() == false) {
        long start = System.nanoTime();
        try {
          synchronized (initialized) {
            initialized.wait(timeUnit.toMillis(timeout));
          }
        } catch (InterruptedException ex) {
          throw Throwables.throwFormatted(
              msg -> new IOException(msg, ex),
              "Initialization of transport interrupted after {}ns!",
              System.nanoTime() - start);
        }

        long elapsedNS = System.nanoTime() - start;
        if (session == null) {
          throw Throwables.throwFormatted(
              msg -> new IOException(msg),
              "Transport initialization timed out in {}ns!",
              elapsedNS);
        }

        initialized.set(true);
        timeout -= timeUnit.convert(elapsedNS, TimeUnit.NANOSECONDS);
      }

      long start = System.nanoTime();
      try {
        return session.response(timeout, timeUnit).get();
      } catch (InterruptedException ex) {
        throw Throwables.throwFormatted(
            msg -> new IOException(msg, ex),
            "Session {} interrupted after {}ns while waiting for response for {} {}!",
            session,
            System.nanoTime() - start,
            timeout,
            timeUnit);
      } catch (ExecutionException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof IOException) {
          throw (IOException) cause;
        }

        throw Throwables.throwFormatted(
            msg -> new IOException(msg, cause),
            "Session {} failed to read response within {}ns with timeout {} {}",
            session,
            System.nanoTime() - start,
            timeout,
            timeUnit);
      }
    }
  }

  private final ByteArrayOutputStream outputStream;
  private ByteBufferChannel channel;

  public NetConfSession(ByteBufferChannel channel, Charset charset)
      throws JNCException, IOException {
    this(new ByteArrayOutputStream(4096), charset);
    this.channel = channel;
    initializeTransport();
  }

  private void initializeTransport() {
    ((NetConfTransport) super.in).setNetConfSession(this);
  }

  private NetConfSession(ByteArrayOutputStream stream, Charset charset)
      throws JNCException, IOException {
    super(new NetConfTransport(), new PrintStream(stream, false, charset));
    this.outputStream = stream;
    this.charset = charset;
    this.codec = new DefaultMessageCodec(charset);
  }

  abstract static class AbstractByteBufferProcessor<T> implements ByteBufferProcessor {
    protected final CompletableFuture<T> future;
    protected final long timeout;
    protected final TimeUnit timeUnit;

    AbstractByteBufferProcessor(CompletableFuture<T> future, long timeout, TimeUnit timeUnit) {
      this.future = future;
      this.timeout = timeout;
      this.timeUnit = timeUnit;
    }

    @Override
    public void failed(Throwable exc) {
      future.completeExceptionally(exc);
    }
  }

  private static class ProgressingQueue<T> {
    private boolean inProgress;
    private LinkedList<T> queue = new LinkedList<>();

    public synchronized T poll() {
      T t = queue.poll();
      if (t == null) {
        inProgress = false;
      }

      return t;
    }

    public synchronized boolean offer(T t) {
      if (inProgress) {
        return queue.offer(t);
      }

      inProgress = true;
      return false;
    }

    public synchronized boolean isInProgress() {
      return inProgress;
    }
  }
  /**
   * Since we send only one buffer at a time, we try to create less garbage by reusing the buffer as
   * much as possible.
   */
  private ByteBuffer requestBuffer = ByteBuffer.allocate(4096);

  final ProgressingQueue<RequestByteBuffferProcessor> writes = new ProgressingQueue<>();

  class RequestByteBuffferProcessor extends AbstractByteBufferProcessor<Void> {
    private final String string;

    RequestByteBuffferProcessor(
        String string, CompletableFuture<Void> future, long timeout, TimeUnit timeUnit) {
      super(future, timeout, timeUnit);
      this.string = string;
    }

    @Override
    public boolean process(ByteBuffer sendBuffer) {
      return !codec.encode(requestBuffer, sendBuffer);
    }

    @Override
    public void completed() {
      future.complete(null);

      RequestByteBuffferProcessor processor = writes.poll();
      if (processor != null) {
        scheduleWrite(processor);
      }
    }
  }

  private void scheduleWrite(RequestByteBuffferProcessor processor) {
    byte[] bytes = processor.string.getBytes(charset);
    if (requestBuffer.capacity() >= bytes.length) {
      requestBuffer.clear();
      requestBuffer.put(bytes);
      requestBuffer.flip();
    } else {
      requestBuffer = ByteBuffer.wrap(bytes);
    }

    channel.write(processor);
    processor.future.orTimeout(processor.timeout, processor.timeUnit);
  }

  private ByteBuffer decoded = ByteBuffer.allocate(4096);

  class ResponseByteBufferProcessor extends AbstractByteBufferProcessor<String> {
    ResponseByteBufferProcessor(CompletableFuture<String> future, long timeout, TimeUnit timeUnit) {
      super(future, timeout, timeUnit);
    }

    @Override
    public boolean process(ByteBuffer buffer) {
      if (codec.decode(buffer, decoded)) {
        return false;
      }

      if (!decoded.hasRemaining()) {
        decoded = ByteBuffer.allocate(decoded.capacity() << 1).put(decoded.flip());
      }

      return true;
    }

    @Override
    public void completed() {
      decoded.flip();
      future.complete(charset.decode(decoded).toString());
      decoded.clear();

      ResponseByteBufferProcessor processor = reads.poll();
      if (processor != null) {
        scheduleRead(processor);
      }
    }
  }

  private void scheduleRead(ResponseByteBufferProcessor processor) {
    channel.read(processor);
    processor.future.orTimeout(processor.timeout, processor.timeUnit);
  }

  final ProgressingQueue<ResponseByteBufferProcessor> reads = new ProgressingQueue<>();

  public CompletableFuture<Void> request(String request, long timeout, TimeUnit timeUnit) {
    CompletableFuture<Void> future = new CompletableFuture<>();

    try {
      RequestByteBuffferProcessor processor =
          new RequestByteBuffferProcessor(request, future, timeout, timeUnit);

      if (!writes.offer(processor)) {
        scheduleWrite(processor);
      }
    } catch (Throwable th) {
      future.completeExceptionally(th);
    }

    return future;
  }

  public CompletableFuture<String> response(long timeout, TimeUnit timeUnit) {
    CompletableFuture<String> future = new CompletableFuture<>();

    try {
      ResponseByteBufferProcessor processor =
          new ResponseByteBufferProcessor(future, timeout, timeUnit);

      if (!reads.offer(processor)) {
        scheduleRead(processor);
      }
    } catch (Throwable th) {
      future.completeExceptionally(th);
    }

    return future;
  }

  public CompletableFuture<Element> readReply(long timeout, TimeUnit timeUnit) {
    return response(timeout, timeUnit)
        .thenApply(
            reply -> {
              try {
                return parser.parse(reply);
              } catch (JNCException ex) {
                throw new RuntimeException(ex);
              }
            });
  }

  public CompletableFuture<Element> receiveNotification(long timeout, TimeUnit timeUnit) {
    return response(timeout, timeUnit)
        .thenApply(
            reply -> {
              try {
                Element t = receive_notification_parse(reply);
                return receive_notification_post_process(t);
              } catch (JNCException ex) {
                throw new RuntimeException(ex);
              }
            });
  }

  public CompletableFuture<Element> action(
      Element data, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> action_request(data),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<AutoCloseable> hello(
      long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    setCapability(NETCONF_BASE_1_1_CAPABILITY);
    encode_hello(out);
    out.flush();
    try {
      return rpc(outputStream.toString(charset), requestTimeout, responseTimeout, timeUnit)
          .thenApplyAsync(
              reply -> {
                AutoCloseable closeSession =
                    () -> close(requestTimeout, responseTimeout, timeUnit).join();
                try (Closeables closeables = new Closeables(closeSession)) {
                  establish_capabilities(reply);
                  if (capabilities.hasCapability(NETCONF_BASE_1_1_CAPABILITY)) {
                    codec = new ChunkedFramingMessageCodec();
                  }

                  closeables.protect();
                } catch (JNCException ex) {
                  throw new RuntimeException(ex);
                }

                return closeSession;
              });
    } finally {
      outputStream.reset();
    }
  }

  @FunctionalInterface
  static interface RequestFunction {
    int get() throws Exception;
  }

  @FunctionalInterface
  static interface ReplyFunction<T> {
    T apply(String reply, int mid) throws Exception;
  }

  private <T> CompletableFuture<T> rpc_request_reponse(
      RequestFunction supplier,
      ReplyFunction<T> function,
      long requestTimeout,
      long responseTimeout,
      TimeUnit timeUnit) {
    try {
      int mid = supplier.get();
      return rpc(outputStream.toString(charset), requestTimeout, responseTimeout, timeUnit)
          .thenApplyAsync(
              reply -> {
                try {
                  return function.apply(reply, mid);
                } catch (Exception ex) {
                  throw new ResponseConsumptionException(ex);
                }
              });
    } catch (Exception ex) {
      return CompletableFuture.failedFuture(new RequestGenerationException(ex));
    } finally {
      outputStream.reset();
    }
  }

  public CompletableFuture<Element> close(
      long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> close_session_request(),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> commit(
      long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> commit_request(),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> confirmedCommit(
      int confirmationTimeoutInSeconds,
      long requestTimeout,
      long responseTimeout,
      TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> confirmed_commit_request(confirmationTimeoutInSeconds),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> copyConfig(
      String sourceUrl, int target, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> copy_config_request(sourceUrl, target),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> copyConfig(
      String sourceUrl,
      String targetUrl,
      long requestTimeout,
      long responseTimeout,
      TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> copy_config_request(sourceUrl, targetUrl),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> copyConfig(
      int source, String targetUrl, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> copy_config_request(source, targetUrl),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> copyConfig(
      int source, int target, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> copy_config_request(source, target),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> copyConfig(
      NodeSet sourceTrees,
      String targetUrl,
      long requestTimeout,
      long responseTimeout,
      TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> copy_config_request(sourceTrees, targetUrl),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> copyConfig(
      NodeSet sourceTrees,
      int target,
      long requestTimeout,
      long responseTimeout,
      TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> copy_config_request(sourceTrees, target),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> copyConfig(
      Element sourceTree,
      int target,
      long requestTimeout,
      long responseTimeout,
      TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> copy_config_request(new NodeSet(sourceTree), target),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> copyConfig(
      Element sourceTree,
      String targetUrl,
      long requestTimeout,
      long responseTimeout,
      TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> copy_config_request(new NodeSet(sourceTree), targetUrl),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> kill(
      long sessionId, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> kill_session_request(sessionId),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> createSubscription(
      String streamName,
      String eventFilter,
      String startTime,
      String stopTime,
      long requestTimeout,
      long responseTimeout,
      TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> create_subscription_request(streamName, eventFilter, startTime, stopTime),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> createSubscription(
      String streamName,
      NodeSet eventFilter,
      String startTime,
      String stopTime,
      long requestTimeout,
      long responseTimeout,
      TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> create_subscription_request(streamName, eventFilter, startTime, stopTime),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> createSubscription(
      String streamName, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> create_subscription_request(streamName, (String) null, null, null),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> createSubscription(
      long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> create_subscription_request(null, (String) null, null, null),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> deleteConfig(
      int dataStore, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> delete_config_request(dataStore),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> unlock(
      int dataStore, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> unlock_request(dataStore),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> unlockPartial(
      int lockId, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> unlock_partial_request(lockId),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> validate(
      int datastore, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> validate_request(datastore),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> validate(
      String url, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> validate_request(url),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> validate(
      Element configTree, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> validate_request(configTree),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> deleteConfig(
      String targetURL, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> delete_config_request(targetURL),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> discardChanges(
      long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> discard_changes_request(),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> editConfig(
      int datastore,
      Element configTree,
      long requestTimeout,
      long responseTimeout,
      TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> edit_config_request(datastore, configTree),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> editConfig(
      int datastore,
      NodeSet configTrees,
      long requestTimeout,
      long responseTimeout,
      TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> edit_config_request(datastore, configTrees),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> editConfig(
      int datastore, String url, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> edit_config_request(datastore, url),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<NodeSet> get(
      String xpath, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> get_request(xpath),
        (reply, mid) -> parse_rpc_reply(parser, reply, Integer.toString(mid), "/data"),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<NodeSet> get(
      Element subtreeFilter, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> get_request(subtreeFilter),
        (reply, mid) -> parse_rpc_reply(parser, reply, Integer.toString(mid), "/data"),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<NodeSet> getConfig(
      int datastore, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> get_config_request(datastore),
        (reply, mid) -> parse_rpc_reply(parser, reply, Integer.toString(mid), "/data"),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<NodeSet> getConfig(
      long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> get_config_request(RUNNING),
        (reply, mid) -> parse_rpc_reply(parser, reply, Integer.toString(mid), "/data"),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<NodeSet> getConfig(
      int datastore,
      Element subtreeFilter,
      long requestTimeout,
      long responseTimeout,
      TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> get_config_request(datastore, subtreeFilter),
        (reply, mid) -> parse_rpc_reply(parser, reply, Integer.toString(mid), "/data"),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<NodeSet> getConfig(
      int datastore, String xpath, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> get_config_request(datastore, xpath),
        (reply, mid) -> parse_rpc_reply(parser, reply, Integer.toString(mid), "/data"),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<NodeSet> getConfig(
      String xpath, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> get_config_request(RUNNING, xpath),
        (reply, mid) -> parse_rpc_reply(parser, reply, Integer.toString(mid), "/data"),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<NodeSet> getStreams(
      long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> get_request(get_streams_filter()),
        (reply, mid) -> parse_rpc_reply(parser, reply, Integer.toString(mid), "/data"),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Element> lock(
      int dataStore, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> lock_request(dataStore),
        (reply, mid) -> recv_rpc_reply_ok(reply, Integer.toString(mid)),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Integer> lockPartial(
      String[] select, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return rpc_request_reponse(
        () -> lock_partial_request(select),
        (reply, mid) ->
            lock_partial_post_process(
                parse_rpc_reply(parser, reply, Integer.toString(mid), "/data")),
        requestTimeout,
        responseTimeout,
        timeUnit);
  }

  public CompletableFuture<Integer> lockPartial(
      String select, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return lockPartial(new String[] {select}, requestTimeout, responseTimeout, timeUnit);
  }

  // make sure that there is a handoff from caller thread to the common forkpool immediately after
  // rpc is called
  // and right before the return is called.
  public CompletableFuture<String> rpc(
      String request, long requestTimeout, long responseTimeout, TimeUnit timeUnit) {
    return CompletableFuture.supplyAsync(
            () ->
                request(request, requestTimeout, timeUnit)
                    .handleAsync(
                        (requestFuture, ex) -> {
                          if (ex == null) {
                            return requestFuture;
                          }

                          if (ex instanceof TimeoutException) {
                            throw new RequestTimeoutException(
                                requestTimeout, timeUnit, ex.getCause());
                          }

                          if (ex instanceof CompletionException) {
                            ex = ex.getCause();
                          }

                          throw new RequestPhaseException(ex);
                        })
                    .thenComposeAsync(
                        voids ->
                            response(responseTimeout, timeUnit)
                                .handle(
                                    (v, ex) -> {
                                      if (ex == null) {
                                        return v;
                                      }

                                      if (ex instanceof TimeoutException) {
                                        throw new ResponseTimeoutException(
                                            requestTimeout, timeUnit, ex.getCause());
                                      }

                                      if (ex instanceof CompletionException) {
                                        ex = ex.getCause();
                                      }

                                      throw new ResponsePhaseException(ex);
                                    })))
        .thenApply(cfs -> cfs.join());
  }

  private static final Logger logger = LogManager.getLogger();
}