package org.nustaq.kontraktor.asyncio;

import org.nustaq.kontraktor.Actor;
import org.nustaq.kontraktor.Actors;
import org.nustaq.kontraktor.IPromise;
import org.nustaq.kontraktor.Promise;
import org.nustaq.kontraktor.util.Log;
import org.nustaq.offheap.BinaryQueue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Created by ruedi on 08/05/15.
 *
 * ALPHA has serious issues.
 *
 */
public class _AsyncClientSocket implements Runnable {

    SocketChannel channel;
    Selector selector;
    BiFunction<SelectionKey,SocketChannel,AsyncSocketConnection> connectionFactory;
    AsyncSocketConnection con;
    Promise connectFuture;

    Thread debug;
    public IPromise connect(String host, int port, BiFunction<SelectionKey,SocketChannel,AsyncSocketConnection> connectionFactory) {
        if ( connectFuture != null ) {
            throw new RuntimeException("illegal state, connect is underway");
        }
        if ( Actor.sender.get() == null )
            throw new RuntimeException("must be called from within actor thread");
        debug = Thread.currentThread();
        connectFuture = new Promise<>();
        this.connectionFactory = connectionFactory;
        try {
            channel = SocketChannel.open();
            channel.configureBlocking(false);
            selector = Selector.open();
            channel.register(selector, SelectionKey.OP_CONNECT | SelectionKey.OP_READ | SelectionKey.OP_WRITE );
            channel.connect(new InetSocketAddress(host, port));
            Actor.sender.get().execute(this);
        } catch (Exception e) {
            connectFuture.reject(e);
            connectFuture = null;
        }
        return connectFuture;
    }

    @Override
    public void run() {
        if ( Thread.currentThread() != debug )
            throw new RuntimeException("wrong thread");
        boolean hadStuff = false;
        try {
            selector.selectNow();
            Set<SelectionKey> selectionKeys = selector.selectedKeys();
            for (Iterator<SelectionKey> iterator = selectionKeys.iterator(); iterator.hasNext(); ) {
                SelectionKey key = iterator.next();
                if (key.isConnectable() && connectFuture != null ) {
                    boolean connected = channel.finishConnect();
                    con = connectionFactory.apply(key,channel);
                    iterator.remove();
                    connectFuture.resolve();
                    connectFuture = null;
                }
                if ( con != null ) {
                    boolean wrote = false;
                    if (key.isWritable()) {
                        ByteBuffer writingBuffer = con.getWritingBuffer();
                        if ( writingBuffer != null ) {
                            int written = channel.write(writingBuffer);
                            if (written<0) {
                                wrote = true;
                                iterator.remove();
                                key.cancel();
                                // closed
                                con.writeFinished("disconnected");
                            } else
                            if ( writingBuffer.remaining() == 0) {
                                wrote = true;
                                iterator.remove();
                                con.writeFinished(null);
                            }
                        }
                    }
                    if (!wrote && key.isReadable()) {
                        hadStuff = true;
                        try {
                            if ( ! con.readData() ) {
                                iterator.remove();
                            }
                        } catch (Exception ioe) {
                            ioe.printStackTrace();
                            con.closed(ioe);
                            key.cancel();
                            try {
                                channel.close();
                            } catch (IOException e) {
                                Log.Warn(this, e);
                            }
                        }
                    }
                }
            }
        } catch (Throwable e) {
            Log.Warn(this,e,"");
            Actors.reject(e);
            try {
                close();
            } catch (IOException e1) {
                Log.Warn(this, e, "");
            }
        }
        if ( ! isClosed() ) {
            if ( hadStuff ) {
                Actor.sender.get().execute(this);
            } else {
                Actor.sender.get().delayed( 2, this );
            }
        } else
            System.out.println("loop terminated");
    }

    public boolean isClosed() {
        return !channel.isOpen();
    }

    public void close() throws IOException {
        channel.close();
    }

    public AsyncSocketConnection getConnection() {
        return con;
    }


    public static class CLSActor extends Actor<CLSActor> {
        _AsyncClientSocket sock;

        public void $connect() {
            sock = new _AsyncClientSocket();
            sock.connect("localhost",8080, (key,channel) ->
                new QueuingAsyncSocketConnection( key, channel ) {
                    @Override
                    protected void dataReceived(BinaryQueue queue) {
                        System.out.println("received:"+queue.remaining());
                    }
                }
            ).await();
            delayed( 1000, () -> $loop() );
        }

        public void $loop() {
            QueuingAsyncSocketConnection con = (QueuingAsyncSocketConnection) sock.getConnection();
            con.write("Hello\n".getBytes());
            con.tryFlush();
            delayed(1000, () -> $loop());
        }
    }

    public static void main(String a[]) throws InterruptedException {
        CLSActor act = Actors.AsActor(CLSActor.class);
        act.$connect();
        Thread.sleep(10000000l);
    }

}
