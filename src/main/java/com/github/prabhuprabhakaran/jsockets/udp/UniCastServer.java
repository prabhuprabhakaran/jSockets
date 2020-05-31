package com.github.prabhuprabhakaran.jsockets.udp;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.concurrent.ThreadFactory;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

public class UniCastServer {

    private final static Logger LOGGER = Logger.getLogger(UniCastServer.class.getName());

    public final static String PORT_PROP = "port";
    private final static int PORT_DEFAULT = 8000;
    private int port = PORT_DEFAULT;

    public final static String GROUPS_PROP = "groups";
    private final static String GROUPS_DEFAULT = null;
    private String groups = GROUPS_DEFAULT;

    public static enum State {

        STARTING, STARTED, STOPPING, STOPPED
    };
    private State currentState = State.STOPPED;
    public final static String STATE_PROP = "state";
    private Collection<UniCastServer.Listener> listeners = new LinkedList<UniCastServer.Listener>();
    private UniCastServer.Event event = new UniCastServer.Event(this);
    private PropertyChangeSupport propSupport = new PropertyChangeSupport(this);
    private final UniCastServer This = this;
    private ThreadFactory threadFactory;
    private Thread ioThread;
    private MulticastSocket mSocket;
    private DatagramPacket packet = new DatagramPacket(new byte[64 * 1024], 64 * 1024);
    public final static String LAST_EXCEPTION_PROP = "lastException";
    private Throwable lastException;

    public UniCastServer() {
    }

    public UniCastServer(int port) {
        this.port = port;
    }

    public UniCastServer(int port, ThreadFactory factory) {
        this.port = port;
        this.threadFactory = factory;
    }

    public synchronized void start() {
        if (this.currentState == UniCastServer.State.STOPPED) {
            assert ioThread == null : ioThread;

            Runnable run = new Runnable() {
                @Override
                public void run() {
                    runServer();
                    ioThread = null;
                    setState(UniCastServer.State.STOPPED);
                }
            };

            if (this.threadFactory != null) {
                this.ioThread = this.threadFactory.newThread(run);

            } else {
                this.ioThread = new Thread(run, this.getClass().getName());
            }

            setState(UniCastServer.State.STARTING);
            this.ioThread.start();
        }
    }

    public synchronized void stop() {
        if (this.currentState == UniCastServer.State.STARTED) {
            setState(UniCastServer.State.STOPPING);
            if (this.mSocket != null) {
                this.mSocket.close();
            }
        }
    }

    public synchronized UniCastServer.State getState() {
        return this.currentState;
    }

    protected synchronized void setState(UniCastServer.State state) {
        State oldVal = this.currentState;
        this.currentState = state;
        firePropertyChange(STATE_PROP, oldVal, state);
    }

    public synchronized void reset() {
        switch (this.currentState) {
            case STARTED:
                this.addPropertyChangeListener(STATE_PROP, new PropertyChangeListener() {
                    public void propertyChange(PropertyChangeEvent evt) {
                        State newState = (State) evt.getNewValue();
                        if (newState == State.STOPPED) {
                            UniCastServer server = (UniCastServer) evt.getSource();
                            server.removePropertyChangeListener(STATE_PROP, this);
                            server.start();
                        }
                    }
                });
                stop();
                break;
        }
    }

    protected void runServer() {
        try {
            this.mSocket = new MulticastSocket(getPort());
            LOGGER.info("UDP Server established on port " + getPort());

            try {
                this.mSocket.setReceiveBufferSize(this.packet.getData().length);
                LOGGER.info("UDP Server receive buffer size (bytes): " + this.mSocket.getReceiveBufferSize());
            } catch (IOException exc) {
                int pl = this.packet.getData().length;
                int bl = this.mSocket.getReceiveBufferSize();
                LOGGER.warn(String.format("Could not set receive buffer to %d. It is now at %d. Error: %s",
                        pl, bl, exc.getMessage()));
            }

            String gg = getGroups();
            if (gg != null) {
                String[] proposed = gg.split("[\\s,]+");
                for (String p : proposed) {
                    try {
                        this.mSocket.joinGroup(InetAddress.getByName(p));
                        LOGGER.info("UDP Server joined multicast group " + p);
                    } catch (IOException exc) {
                        LOGGER.warn("Could not join " + p + " as a multicast group: " + exc.getMessage());
                    }
                }
            }

            setState(State.STARTED);
            LOGGER.info("UDP Server listening...");

            while (!this.mSocket.isClosed()) {
                synchronized (This) {
                    if (this.currentState == State.STOPPING) {
                        LOGGER.info("Stopping UDP Server by request.");
                        this.mSocket.close();
                    }
                }

                if (!this.mSocket.isClosed()) {

                    this.mSocket.receive(packet);

                    if (LOGGER.isEnabledFor(Level.DEBUG)) {
                        LOGGER.debug("UDP Server received datagram: " + packet);
                    }
                    fireUdpServerPacketReceived();

                }
            }

        } catch (Exception exc) {
            synchronized (This) {
                if (this.currentState == State.STOPPING) {
                    this.mSocket.close();
                    LOGGER.info("Udp Server closed normally.");
                } else {
                    LOGGER.log(Level.WARN, "Server closed unexpectedly: " + exc.getMessage(), exc);
                }
            }
            fireExceptionNotification(exc);
        } finally {
            setState(State.STOPPING);
            if (this.mSocket != null) {
                this.mSocket.close();
            }
            this.mSocket = null;
        }
    }

    public synchronized DatagramPacket getPacket() {
        return this.packet;
    }

    public synchronized void send(DatagramPacket packet) throws IOException {
        if (this.mSocket == null) {
            throw new IOException("No socket available to send packet; is the server running?");
        } else {
            this.mSocket.send(packet);
        }
    }

    public synchronized int getReceiveBufferSize() throws SocketException {
        if (this.mSocket == null) {
            throw new SocketException("getReceiveBufferSize() cannot be called when the server is not started.");
        } else {
            return this.mSocket.getReceiveBufferSize();
        }
    }

    public synchronized void setReceiveBufferSize(int size) throws SocketException {
        if (this.mSocket == null) {
            throw new SocketException("setReceiveBufferSize(..) cannot be called when the server is not started.");
        } else {
            this.mSocket.setReceiveBufferSize(size);
        }
    }

    public synchronized int getPort() {
        return this.port;
    }

    public synchronized void setPort(int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Cannot set port outside range 0..65535: " + port);
        }

        int oldVal = this.port;
        this.port = port;
        if (getState() == State.STARTED) {
            reset();
        }

        firePropertyChange(PORT_PROP, oldVal, port);
    }

    public synchronized String getGroups() {
        return this.groups;
    }

    public synchronized void setGroups(String group) {

        String oldVal = this.groups;
        this.groups = group;
        if (getState() == State.STARTED) {
            reset();
        }

        firePropertyChange(GROUPS_PROP, oldVal, this.groups);
    }

    public synchronized void addUdpServerListener(UniCastServer.Listener l) {
        listeners.add(l);
    }

    public synchronized void removeUdpServerListener(UniCastServer.Listener l) {
        listeners.remove(l);
    }

    protected synchronized void fireUdpServerPacketReceived() {

        UniCastServer.Listener[] ll = listeners.toArray(new UniCastServer.Listener[listeners.size()]);
        for (UniCastServer.Listener l : ll) {
            try {
                l.packetReceived(event);
            } catch (Exception exc) {
                LOGGER.warn("UdpServer.Listener " + l + " threw an exception: " + exc.getMessage());
                fireExceptionNotification(exc);
            }
        }
    }

    public synchronized void fireProperties() {
        firePropertyChange(PORT_PROP, null, getPort());
        firePropertyChange(GROUPS_PROP, null, getGroups());
        firePropertyChange(STATE_PROP, null, getState());
    }

    protected synchronized void firePropertyChange(final String prop, final Object oldVal, final Object newVal) {
        try {
            propSupport.firePropertyChange(prop, oldVal, newVal);
        } catch (Exception exc) {
            LOGGER.log(Level.WARN,
                    "A property change listener threw an exception: " + exc.getMessage(), exc);
            fireExceptionNotification(exc);
        }
    }

    public synchronized void addPropertyChangeListener(PropertyChangeListener listener) {
        propSupport.addPropertyChangeListener(listener);
    }

    public synchronized void addPropertyChangeListener(String property, PropertyChangeListener listener) {
        propSupport.addPropertyChangeListener(property, listener);
    }

    public synchronized void removePropertyChangeListener(PropertyChangeListener listener) {
        propSupport.removePropertyChangeListener(listener);
    }

    public synchronized void removePropertyChangeListener(String property, PropertyChangeListener listener) {
        propSupport.removePropertyChangeListener(property, listener);
    }

    public synchronized Throwable getLastException() {
        return this.lastException;
    }

    protected void fireExceptionNotification(Throwable t) {
        Throwable oldVal = this.lastException;
        this.lastException = t;
        firePropertyChange(LAST_EXCEPTION_PROP, oldVal, t);
    }

    public static void setLoggingLevel(Level level) {
        LOGGER.setLevel(level);
    }

    public static Level getLoggingLevel() {
        return LOGGER.getLevel();
    }

    public static interface Listener extends java.util.EventListener {

        public abstract void packetReceived(UniCastServer.Event evt);
    }

    public static class Event extends java.util.EventObject {

        private final static long serialVersionUID = 1;

        public Event(UniCastServer src) {
            super(src);
        }

        public UniCastServer getUdpServer() {
            return (UniCastServer) getSource();
        }

        public UniCastServer.State getState() {
            return getUdpServer().getState();
        }

        public DatagramPacket getPacket() {
            return getUdpServer().getPacket();
        }

        public byte[] getPacketAsBytes() {
            DatagramPacket packet = getPacket();
            if (packet == null) {
                return null;
            } else {
                byte[] data = new byte[packet.getLength()];
                System.arraycopy(
                        packet.getData(), packet.getOffset(),
                        data, 0, data.length);
                return data;
            }
        }

        public String getPacketAsString() {
            DatagramPacket packet = getPacket();
            if (packet == null) {
                return null;
            } else {
                String s = new String(
                        packet.getData(),
                        packet.getOffset(),
                        packet.getLength());
                return s;
            }
        }

        public void send(DatagramPacket packet) throws IOException {
            this.getUdpServer().send(packet);
        }
    }
}
