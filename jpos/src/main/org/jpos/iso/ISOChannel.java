package org.jpos.iso;

import java.io.*;
import java.util.*;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import org.jpos.util.Logger;
import org.jpos.util.LogEvent;
import org.jpos.util.LogProducer;
import org.jpos.util.NameRegistrar;
import org.jpos.iso.ISOFilter.VetoException;

/**
 * ISOChannel is an abstract class that provides functionality that
 * allows the transmision and reception of ISO 8583 Messages
 * over a TCP/IP session.
 * <p>
 * It is not thread-safe, ISOMUX takes care of the
 * synchronization details
 * <p>
 * ISOChannel is Observable in order to suport GUI components
 * such as ISOChannelPanel.
 * <br>
 * It now support the new Logger architecture so we will
 * probably setup ISOChannelPanel to be a LogListener insteado
 * of being an Observer in future releases.
 * 
 * @author <a href="mailto:apr@cs.com.uy">Alejandro P. Revilla</a>
 * @version $Revision$ $Date$
 * @see ISOMsg
 * @see ISOMUX
 * @see ISOException
 * @see CSChannel
 * @see Logger
 *
 */

public abstract class ISOChannel extends Observable implements LogProducer {
    private Socket socket;
    private String host;
    private int port;
    private boolean usable;
    private String name;
    protected DataInputStream serverIn;
    protected DataOutputStream serverOut;
    protected ISOPackager packager;
    protected ServerSocket serverSocket = null;
    protected Vector incomingFilters, outgoingFilters;

    public static final int CONNECT      = 0;
    public static final int TX           = 1;
    public static final int RX           = 2;
    public static final int SIZEOF_CNT   = 3;

    private int[] cnt;

    protected Logger logger = null;
    protected String realm = null;

    /**
     * constructor shared by server and client
     * ISOChannels (which have different signatures)
     */
    public ISOChannel () {
	super();
        cnt = new int[SIZEOF_CNT];
	name = "";
	incomingFilters = new Vector();
	outgoingFilters = new Vector();
    }

    /**
     * constructs a client ISOChannel
     * @param host  server TCP Address
     * @param port  server port number
     * @param p     an ISOPackager
     * @see ISOPackager
     */
    public ISOChannel (String host, int port, ISOPackager p) {
        this();
        setHost(host, port);
        setPackager(p);
    }
    /**
     * initialize an ISOChannel
     * @param host  server TCP Address
     * @param port  server port number
     */
    public void setHost(String host, int port) {
        this.host = host;
        this.port = port;
    }
    /**
     * @return hostname (may be null)
     */
    public String getHost() {
	return host;
    }
    /**
     * @return port number
     */
    public int getPort() {
	return port;
    }
    /**
     * set Packager for channel
     * @param p     an ISOPackager
     * @see ISOPackager
     */
    public void setPackager(ISOPackager p) {
        this.packager = p;
    }

    /**
     * @return current packager
     */
    public ISOPackager getPackager() {
	return packager;
    }

    /**
     * constructs a server ISOChannel
     * @param p     an ISOPackager
     * @exception IOException
     * @see ISOPackager
     */
    public ISOChannel (ISOPackager p) throws IOException {
        this();
        this.host = null;
        this.port = 0;
        this.packager = p;
	name = "";
    }
    /**
     * constructs a server ISOChannel associated with a Server Socket
     * @param p     an ISOPackager
     * @param serverSocket where to accept a connection
     * @exception IOException
     * @see ISOPackager
     */
    public ISOChannel (ISOPackager p, ServerSocket serverSocket) 
        throws IOException 
    {
        this();
        this.host = null;
        this.port = 0;
        this.packager = p;
        this.serverSocket = serverSocket;
	name = "";
    }
    /**
     * reset stat info
     */
    public void resetCounters() {
        for (int i=0; i<SIZEOF_CNT; i++)
            cnt[i] = 0;
    }
    /**
     * get the counters in order to pretty print them
     * or for stats purposes
     */
    public int[] getCounters() {
        return cnt;
    }
    /**
     * @return the connection state
     */
    public boolean isConnected() {
        return socket != null && usable;
    }
    /**
     * setup I/O Streams from socket
     * @param socket a Socket (client or server)
     * @exception IOException
     */
    protected void connect (Socket socket) throws IOException {
        this.socket = socket;

        serverIn = new DataInputStream (
            new BufferedInputStream (socket.getInputStream ())
        );
        serverOut = new DataOutputStream(
            new BufferedOutputStream(socket.getOutputStream())
        );
        usable = true;
        cnt[CONNECT]++;
        setChanged();
        notifyObservers();
    }

    /**
     * Connects client ISOChannel to server
     * @exception IOException
     */
    public void connect () throws IOException {
	LogEvent evt = new LogEvent (this, "connect");
	try {
            if (serverSocket != null) {
		evt.addMessage ("local port "+serverSocket.getLocalPort()
		    +" remote host "+serverSocket.getInetAddress());
		accept(serverSocket);
	    }
	    else {
		evt.addMessage (host+":"+port);
		connect(new Socket (host, port));
	    }
	    Logger.log (evt);
	} catch (ConnectException e) {
	    Logger.log (new LogEvent (this, "connection-refused",
		getHost()+":"+getPort())
	    );
	} catch (IOException e) {
	    evt.addMessage (e);
	    Logger.log (evt);
	    throw e;
	}
    }

    /**
     * Accepts connection 
     * @exception IOException
     */
    public void accept(ServerSocket s) throws IOException {
        connect(s.accept());
    }

    /**
     * @param b - new Usable state (used by ISOMUX internals to
     * flag as unusable in order to force a reconnection)
     */
    public void setUsable(boolean b) {
	Logger.log (new LogEvent (this, "usable", new Boolean (b)));
        usable = b;
    }
    protected void sendMessageLength(int len) throws IOException { }
    protected void sendMessageHeader(ISOMsg m, int len) throws IOException { }
    protected void sendMessageTrailler(ISOMsg m, int len) throws IOException { }
    protected int getMessageLength() throws IOException, ISOException {
        return -1;
    }
    protected int getHeaderLength()         { return 0; }
    protected int getHeaderLength(byte[] b) { return 0; }
    protected byte[] streamReceive() throws IOException {
        return new byte[0];
    }
    /**
     * sends an ISOMsg over the TCP/IP session
     * @param m the Message to be sent
     * @exception IOException
     * @exception ISOException
     * @exception ISOFilter.VetoException;
     */
    public void send (ISOMsg m) 
	throws IOException, ISOException, VetoException
    {
	LogEvent evt = new LogEvent (this, "send");
	evt.addMessage (m);
	try {
	    if (!isConnected())
		throw new ISOException ("unconnected ISOChannel");
	    m.setDirection(ISOMsg.OUTGOING);
	    applyOutgoingFilters (m, evt);
	    m.setPackager (packager);
	    byte[] b = m.pack();
	    sendMessageLength(b.length + getHeaderLength());
	    sendMessageHeader(m, b.length);
	    serverOut.write(b, 0, b.length);
	    sendMessageTrailler(m, b.length);
	    serverOut.flush ();
	    cnt[TX]++;
	    setChanged();
	    notifyObservers(m);
	} catch (VetoException e) {
	    evt.addMessage (e);
	    throw e;
	} catch (ISOException e) {
	    evt.addMessage (e);
	    throw e;
	} catch (IOException e) {
	    evt.addMessage (e);
	    throw e;
	} finally {
	    Logger.log (evt);
	}
    }
    protected boolean isRejected(byte[] b) {
        // VAP Header support - see VAPChannel
        return false;
    }
    /**
     * Waits and receive an ISOMsg over the TCP/IP session
     * @return the Message received
     * @exception IOException
     * @exception ISOException
     */
    public ISOMsg receive() throws IOException, ISOException {
        byte[] b, header=null;
	LogEvent evt = new LogEvent (this, "receive");
	ISOMsg m = new ISOMsg();
	try {
	    if (!isConnected())
		throw new ISOException ("unconnected ISOChannel");

            int len  = getMessageLength();
            int hLen = getHeaderLength();

            if (len == -1) {
		header = new byte [hLen];
		serverIn.readFully(header,0,hLen);
		b = streamReceive();
	    }
            else if (len > 10 && len <= 4096) {
		int l;
		if (hLen > 0) {
		    // ignore message header (TPDU)
		    header = new byte [hLen];
		    serverIn.readFully(header,0,hLen);
		    if (isRejected(header))
			throw new ISOException ("Unhandled Rejected Message");
		    len -= hLen;
		}
		b = new byte[len];
		serverIn.readFully(b,0,len);
	    }
	    else
		throw new ISOException(
		    "receive length " +len + " seems extrange");

	    m.setPackager (packager);
	    if (b.length > 0)  // Ignore NULL messages
		m.unpack (b);

	    m.setHeader(header);
	    m.setDirection(ISOMsg.INCOMING);
	    applyIncomingFilters (m, evt);
	    evt.addMessage (m);
	    cnt[RX]++;
	    setChanged();
	    notifyObservers(m);
	} catch (ISOException e) {
	    evt.addMessage (e);
	    throw e;
	} catch (EOFException e) {
	    evt.addMessage ("<peer-disconnect/>");
	    throw e;
	} catch (IOException e) { 
	    if (usable) 
		evt.addMessage (e);
	    throw e;
	} finally {
	    Logger.log (evt);
	}
	return m;
    }
    /**
     * Low level receive
     * @param b byte array
     * @exception IOException
     */
    public int getBytes (byte[] b) throws IOException {
        return serverIn.read (b);
    }
    /**
     * disconnects the TCP/IP session. The instance is ready for
     * a reconnection. There is no need to create a new ISOChannel<br>
     * @exception IOException
     */
    public void disconnect () throws IOException {
	LogEvent evt = new LogEvent (this, "disconnect");
        if (serverSocket != null) 
	    evt.addMessage ("local port "+serverSocket.getLocalPort()
		+" remote host "+serverSocket.getInetAddress());
	else
	    evt.addMessage (host+":"+port);
	try {
	    usable = false;
	    setChanged();
	    notifyObservers();
	    serverIn  = null;
	    serverOut = null;
	    if (socket != null)
		socket.close ();
	    socket = null;
	    Logger.log (evt);
	} catch (IOException e) {
	    evt.addMessage (e);
	    Logger.log (evt);
	    throw e;
	}
    }   
    /**
     * Issues a disconnect followed by a connect
     * @exception IOException
     */
    public void reconnect() throws IOException {
        disconnect();
        connect();
    }
    public void setLogger (Logger logger, String realm) {
	this.logger = logger;
	this.realm  = realm;
    }
    public String getRealm () {
	return realm;
    }
    public Logger getLogger() {
	return logger;
    }
    /**
     * associates this ISOChannel with a name using NameRegistrar
     * @param name name to register
     * @see NameRegistrar
     */
    public void setName (String name) {
	this.name = name;
	NameRegistrar.register ("channel."+name, this);
    }
    /**
     * @return ISOChannel instance with given name.
     * @throws NameRegistrar.NotFoundException;
     * @see NameRegistrar
     */
    public static ISOChannel getChannel (String name)
	throws NameRegistrar.NotFoundException
    {
	return (ISOChannel) NameRegistrar.get ("channel."+name);
    }
    /**
     * @return this ISOChannel's name ("" if no name was set)
     */
    public String getName() {
	return this.name;
    }
    /**
     * @param filter filter to add
     * @param direction ISOMsg.INCOMING, ISOMsg.OUTGOING, 0 for both
     */
    public void addFilter (ISOFilter filter, int direction) {
	switch (direction) {
	    case ISOMsg.INCOMING :
		incomingFilters.add (filter);
		break;
	    case ISOMsg.OUTGOING :
		outgoingFilters.add (filter);
		break;
	    case 0 :
		incomingFilters.add (filter);
		outgoingFilters.add (filter);
		break;
	}
    }

    /**
     * @param filter filter to add (both directions, incoming/outgoing)
     */
    public void addFilter (ISOFilter filter) {
	addFilter (filter, 0);
    }
    /**
     * @param filter filter to remove
     * @param direction ISOMsg.INCOMING, ISOMsg.OUTGOING, 0 for both
     */
    public void removeFilter (ISOFilter filter, int direction) {
	switch (direction) {
	    case ISOMsg.INCOMING :
		incomingFilters.remove (filter);
		break;
	    case ISOMsg.OUTGOING :
		outgoingFilters.remove (filter);
		break;
	    case 0 :
		incomingFilters.remove (filter);
		outgoingFilters.remove (filter);
		break;
	}
    }
    /**
     * @param filter filter to remove (both directions)
     */
    public void removeFilter (ISOFilter filter) {
	removeFilter (filter, 0);
    }
    protected void applyOutgoingFilters (ISOMsg m, LogEvent evt) 
	throws VetoException
    {
	Iterator iter  = outgoingFilters.iterator();
	while (iter.hasNext())
	    ((ISOFilter) iter.next()).filter (this, m, evt);
    }
    protected void applyIncomingFilters (ISOMsg m, LogEvent evt) 
	throws VetoException
    {
	Iterator iter  = incomingFilters.iterator();
	while (iter.hasNext())
	    ((ISOFilter) iter.next()).filter (this, m, evt);
    }
}
