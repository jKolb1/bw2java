package edu.berkeley.cs.sdb.bosswave;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class BosswaveClient implements AutoCloseable {
    private static final SimpleDateFormat Rfc3339 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final String hostName;
    private final int port;
    private final Thread listenerThread;

    private final Map<Integer, ResponseHandler> responseHandlers;
    private final Object responseHandlerLock;
    private final Map<Integer, MessageHandler> messageHandlers;
    private final Object messageHandlersLock;
    private final Map<Integer, ListResultHandler> listResultHandlers;
    private final Object listResultHandlersLock;

    private Socket socket;
    private BufferedInputStream inStream;
    private BufferedOutputStream outStream;

    public BosswaveClient(String hostName, int port) {
        this.hostName = hostName;
        this.port = port;
        listenerThread = new Thread(new BWListener());

        responseHandlers = new HashMap<>();
        responseHandlerLock = new Object();
        messageHandlers = new HashMap<>();
        messageHandlersLock = new Object();
        listResultHandlers  = new HashMap<>();
        listResultHandlersLock = new Object();
    }

    public void connect() throws IOException {
        socket = new Socket(hostName, port);
        inStream = new BufferedInputStream(socket.getInputStream());
        outStream = new BufferedOutputStream(socket.getOutputStream());

        // Check that we receive a well-formed acknowledgment
        try {
            Frame frame = Frame.readFromStream(inStream);
            if (frame.getCommand() != Command.HELLO) {
                close();
                throw new RuntimeException("Received invalid Bosswave ACK");
            }
        } catch (InvalidFrameException e) {
            socket.close();
            throw new RuntimeException(e);
        }

        listenerThread.start();
    }

    @Override
    public void close() throws IOException {
        listenerThread.interrupt();
        inStream.close();
        outStream.close();
        socket.close();
    }

    public void publish(PublishRequest request, ResponseHandler handler) throws IOException {
        Command command = Command.PUBLISH;
        if (request.isPersist()) {
            command = Command.PERSIST;
        }
        int seqNo = Frame.generateSequenceNumber();
        Frame.Builder builder = new Frame.Builder(command, seqNo);

        String uri = request.getUri();
        builder.addKVPair("uri", uri);

        if (request.isPersist()) {
            builder.setCommand(Command.PERSIST);
        } else {
            builder.setCommand(Command.PUBLISH);
        }
        builder.addKVPair("persist", Boolean.toString(request.isPersist()));

        Date expiryTime = request.getExpiry();
        if (expiryTime != null) {
            builder.addKVPair("expiry", Rfc3339.format(expiryTime));
        }

        Long expiryDelta = request.getExpiryDelta();
        if (expiryDelta != null) {
            builder.addKVPair("expiryDelta", String.format("%dms", expiryDelta));
        }

        String pac = request.getPrimaryAccessChain();
        if (pac != null) {
            builder.addKVPair("primary_access_chain", pac);
        }

        builder.addKVPair("doverify", Boolean.toString(request.doVerify()));

        ChainElaborationLevel level = request.getChainElaborationLevel();
        if (level != ChainElaborationLevel.UNSPECIFIED) {
            builder.addKVPair("elaborate_pac", level.toString().toLowerCase());
        }

        for (RoutingObject ro : request.getRoutingObjects()) {
            builder.addRoutingObject(ro);
        }
        for (PayloadObject po : request.getPayloadObjects()) {
            builder.addPayloadObject(po);
        }

        Frame f = builder.build();
        f.writeToStream(outStream);
        outStream.flush();
        installResponseHandler(seqNo, handler);
    }

    public void subscribe(SubscribeRequest request, ResponseHandler rh, MessageHandler mh) throws IOException {
        int seqNo = Frame.generateSequenceNumber();
        Frame.Builder builder = new Frame.Builder(Command.SUBSCRIBE, seqNo);

        String uri = request.getUri();
        builder.addKVPair("uri", uri);

        Date expiryTime = request.getExpiry();
        if (expiryTime != null) {
            builder.addKVPair("expiry", Rfc3339.format(expiryTime));
        }

        Long expiryDelta = request.getExpiryDelta();
        if (expiryDelta != null) {
            builder.addKVPair("expirydelta", String.format("%dms", expiryDelta));
        }

        String pac = request.getPrimaryAccessChain();
        if (pac != null) {
            builder.addKVPair("primary_access_chain", pac);
        }

        builder.addKVPair("doverify", Boolean.toString(request.doVerify()));

        ChainElaborationLevel level = request.getChainElaborationLevel();
        if (level != ChainElaborationLevel.UNSPECIFIED) {
            builder.addKVPair("elaborate_pac", level.toString().toLowerCase());
        }

        Boolean leavePacked = request.leavePacked();
        if (!leavePacked) {
            builder.addKVPair("unpack", "true");
        }

        for (RoutingObject ro : request.getRoutingObjects()) {
            builder.addRoutingObject(ro);
        }

        Frame f = builder.build();
        f.writeToStream(outStream);
        outStream.flush();
        if (rh != null) {
            installResponseHandler(seqNo, rh);
        }
        if (mh != null) {
            installMessageHandler(seqNo, mh);
        }
    }

    public void list(ListRequest request, ResponseHandler rh, ListResultHandler lrh) throws IOException {
        int seqNo = Frame.generateSequenceNumber();
        Frame.Builder builder = new Frame.Builder(Command.LIST, seqNo);

        builder.addKVPair("uri", request.getUri());

        String pac = request.getPrimaryAccessChain();
        if (pac != null) {
            builder.addKVPair("primary_access_chain", pac);
        }

        Date expiry = request.getExpiry();
        if (expiry != null) {
            builder.addKVPair("expiry", Rfc3339.format(expiry));
        }
        Long expiryDelta = request.getExpiryDelta();
        if (expiryDelta != null) {
            builder.addKVPair("expirydelta", String.format("%dms", expiryDelta));
        }

        ChainElaborationLevel level = request.getElabLevel();
        if (level != ChainElaborationLevel.UNSPECIFIED) {
            builder.addKVPair("elaborate_pac", level.toString().toLowerCase());
        }

        for (RoutingObject ro : request.getRoutingObjects()) {
            builder.addRoutingObject(ro);
        }

        Frame f = builder.build();
        f.writeToStream(outStream);
        outStream.flush();
        if (rh != null) {
            installResponseHandler(seqNo, rh);
        }
        if (lrh != null) {
            installListResponseHandler(seqNo, lrh);
        }
    }

    private void installResponseHandler(int seqNo, ResponseHandler rh) {
        synchronized (responseHandlerLock) {
            responseHandlers.put(seqNo, rh);
        }
    }

    private void installMessageHandler(int seqNo, MessageHandler mh) {
        synchronized (messageHandlersLock) {
            messageHandlers.put(seqNo, mh);
        }
    }

    private void installListResponseHandler(int seqNo, ListResultHandler lrh) {
        synchronized (listResultHandlersLock) {
            listResultHandlers.put(seqNo, lrh);
        }
    }

    private class BWListener implements Runnable {
        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Frame frame = Frame.readFromStream(inStream);
                    int seqNo = frame.getSeqNo();

                    Command command = frame.getCommand();
                    switch (command) {
                        case RESPONSE: {
                            ResponseHandler responseHandler;
                            synchronized (responseHandlerLock) {
                                responseHandler = responseHandlers.get(seqNo);
                            }
                            if (responseHandler != null) {
                                String status = new String(frame.getFirstValue("status"), StandardCharsets.UTF_8);
                                String reason = null;
                                if (!status.equals("okay")) {
                                    reason = new String(frame.getFirstValue("reason"), StandardCharsets.UTF_8);
                                }
                                responseHandler.onResponseReceived(new Response(status, reason));
                            }
                            break;
                        }

                        case RESULT: {
                            MessageHandler messageHandler;
                            synchronized (messageHandlersLock) {
                                messageHandler = messageHandlers.get(seqNo);
                            }
                            ListResultHandler listResultHandler;
                            synchronized (listResultHandlersLock) {
                                listResultHandler = listResultHandlers.get(seqNo);
                            }

                            if (messageHandler != null) {
                                String uri = new String(frame.getFirstValue("uri"), StandardCharsets.UTF_8);
                                String from = new String(frame.getFirstValue("from"), StandardCharsets.UTF_8);
                                boolean unpack;
                                String unpackStr = new String(frame.getFirstValue("unpack"), StandardCharsets.UTF_8);
                                unpack = Boolean.parseBoolean(unpackStr);

                                Message msg;
                                if (unpack) {
                                    msg = new Message(from, uri, frame.getRoutingObjects(), frame.getPayloadObjects());
                                } else {
                                    msg = new Message(from, uri, null, null);
                                }
                                messageHandler.onResultReceived(msg);
                            } else if (listResultHandler != null) {
                                String finishedStr = new String(frame.getFirstValue("finished"), StandardCharsets.UTF_8);
                                boolean finished = Boolean.parseBoolean(finishedStr);
                                if (finished) {
                                    listResultHandler.finish();
                                } else {
                                    String child = new String(frame.getFirstValue("child"), StandardCharsets.UTF_8);
                                    listResultHandler.onResult(child);
                                }
                            }
                            break;
                        }

                        default:
                            // Ignore frames with any other commands
                    }
                } catch (InvalidFrameException e) {
                    // Ignore invalid frames
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read frame", e);
                }
            }
        }
    }
}
