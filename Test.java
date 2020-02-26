import java.io.*;
import java.net.*;
import javax.net.ssl.*;

public class Test {

  public static Object signal = new Object();
  public static boolean returnedToCache;
  public static boolean finalizePaused;
  public static boolean socketClosed;

  public static void main(String[] args) {
    try {

      // Start a mock HTTPS server
      SSLServerSocketFactory sssf = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
      SSLServerSocket sss = (SSLServerSocket) sssf.createServerSocket(0);
      String host = sss.getInetAddress().toString();
      if(host.equals("0.0.0.0/0.0.0.0")) { host = "localhost"; }
      int port = sss.getLocalPort();
      String url = "https://"+host+":"+port+"/";
      Thread t = new Thread(new Runnable () { public void run() {
        try {
          while(true) {
            SSLSocket ss = (SSLSocket) sss.accept();
            ss.startHandshake();
            Thread t1 = new Thread(new Runnable () { public void run() {
              try {
                InputStream in = ss.getInputStream();
                OutputStream out = ss.getOutputStream();
                byte[] input = new byte[1000];
                while(in.read(input) != -1) {
                  if((new String(input)).contains("\r\n\r\n")) {
                    out.write("HTTP/1.1 200 OK\r\nConnection: Keep-Alive\r\nContent-Length: 1\r\n\r\na".getBytes());
                    out.flush();
                  }
                }
                in.close();
                out.close();
                ss.close();
              } catch (RuntimeException e) {
                //throw e;
              } catch (Exception e) {
                //throw new RuntimeException(e);
              }
            } } );
            t1.setDaemon(true);
            t1.start();
          }
        } catch (RuntimeException e) {
          throw e;
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      } } );
      t.setDaemon(true);
      t.start();

      // Configure the HttpsURLConnection to use a custom SSLSocketFactory so we can intercept
      // specific calls to identify call order and force the timing of specific interactions
      SSLSocketFactory def_ssf = (SSLSocketFactory) SSLSocketFactory.getDefault();
      HttpsURLConnection.setDefaultSSLSocketFactory(new SSLSocketFactoryWrapper(def_ssf));

      // GC usually finalizes the MeteredStream/KeepAliveStream first (which causes the HttpsClient
      // to be added to the KeepAliveCache), then finalizes the SocketWrapper (below), then the
      // SSLSocket referenced by the HttpsClient (which causes the underlying Socket to be closed),
      // then the SSLSocketWrapper (below).
      // However, this GC order is not guaranteed, so we loop until that behavior occurs.
      int i;
      for(i=0; i<3; i++) {
        returnedToCache = false;
        finalizePaused = false;
        socketClosed = false;

        // Make a request to the server (create an HttpsClient that will be reused)
        doRequest(url);

        // Trigger a garbage collection and wait for SocketWrapper finalizer to be called
        // (The finalizer will be paused when this is woken up to force an order of operations that
        // would otherwise happen only rarely when the timing of interactions between two threads
        // happen to line up just right)
        System.gc();
        synchronized(signal) { signal.wait(1000); }

        // Verify that GC called the finalizers in the expected order
        if(socketClosed) {
          System.out.println("SSLSocket was closed before SocketWrapper was finalized. Trying again...");
          // Let GC finish before trying again
          try { Thread.sleep(100); } catch(InterruptedException e) {}
          continue;
        }
        if(!finalizePaused) {
          System.out.println("GC didn't finalize SocketWrapper? Trying again...");
          System.gc();
          synchronized(signal) { signal.wait(1000); }
          if(!finalizePaused) {
            System.out.println("GC still didn't finalize SocketWrapper? Starting over...");
            continue;
          }
        }
        if(!returnedToCache) {
          System.out.println("SocketWrapper finalized before MeteredStream/KeepAliveStream. Trying again...");
          synchronized(signal) { signal.notifyAll(); }
          // Let GC finish before trying again
          try { Thread.sleep(100); } catch(InterruptedException e) {}
          continue;
        }
        System.out.println("GC finalization appears to have been performed in the expected order. Attempting to reproduce the erroneous behavior...");
        break;
      }
      if(i == 3) {
        System.out.println("Garbage collector did not collect in expected order within 3 tries, giving up");
        System.exit(1);
      }

      // Make a new request to the server.  This should re-use the existing HttpsClient which is
      // paused in the SSLSocket finalizer.  After sending the request, this will resume the paused
      // finalizer (forcing a specific order of operations that would normally happen only on rare
      // occasions), which will cause the connection to be terminated before a response is received.
      // At that point, System.exit() will be called to stop this test.  However, Java would
      // normally retry the request automatically, which would mask the problem in simple cases, but
      // can cause problems if the request is non-idempotent or if another bug like the following is
      // triggered by the retry: https://bugs.openjdk.java.net/browse/JDK-8209178
      doRequest(url);

      System.out.println("System.exit() was not called when it was expected. This test seems to have failed to reproduce the erroneous behavior...");
      System.exit(1);
    } catch (Exception ex) {
      System.out.println("Exception: "+ex);
      ex.printStackTrace();
    }
  }

  public static void doRequest(String url) throws IOException {
    URL u = new URL(url);
    HttpsURLConnection c = (HttpsURLConnection)u.openConnection();
    c.setRequestMethod("POST");
    c.setDoOutput(true);
    OutputStreamWriter out = new OutputStreamWriter(c.getOutputStream());
    out.write("test");
    out.close();
    int responseCode = c.getResponseCode();
    String responseMessage = c.getResponseMessage();
    // Fully reading the body causes the HttpsClient to be added to the KeepAliveCache immediately,
    // which avoids this issue since GC will not finalize the HttpsClient.  However, the
    // HttpsURLConnection API does not require the body to be read, and there are cases where API
    // users might not read the body (such as when reading the Location header from a 201 response
    // and ignoring the body).
    //InputStream is = c.getInputStream();
    //while(is.skip(1000) > 0 && is.read() != -1) {}
    //is.close();
  }

  public static class OutputStreamWrapper extends OutputStream {
    protected OutputStream w;
    protected int id;
    public OutputStreamWrapper(OutputStream r, int i) throws IOException { w = r; id = i; }
    public void write(byte[] b, int off, int len) throws IOException {
      try {
        w.write(b, off, len);
      } catch (Exception e) {
        System.out.println("Unexpected SSLSocket write failure");
        System.out.println(e);
        Thread.dumpStack();
        throw e;
      }
    }
    public void write(int b) throws IOException { w.write(b); }
    public void flush() throws IOException { w.flush(); }
    public void close() throws IOException { w.close(); }
  }
  public static class SocketWrapper extends Socket {
    protected Socket w;
    protected int id;
    public SocketWrapper(Socket r, int i) { w = r; id = i; }
    public void finalize() {
      System.out.println("SocketWrapper finalize called. Pausing finalization...");
      Thread.dumpStack();
      finalizePaused = true;
      synchronized(signal) {
        signal.notifyAll();
        try { signal.wait(5000); } catch(Exception e) {}
      }
    }
    public InputStreamWrapper is;
    public OutputStream getOutputStream() throws IOException {
      return new OutputStreamWrapper(w.getOutputStream(), id);
    }
    public void close() throws IOException {
      if(is.inRead) {
        System.out.println("Erroneous behavior has been successfully reproduced: Underlying Socket is being closed after request and before response in main thread");
        System.exit(0);
      } else {
        System.out.println("Underlying Socket closed while not waiting for response");
        Thread.dumpStack();
        socketClosed = true;
        synchronized(signal) { signal.notifyAll(); }
      }
      w.close();
    }
    // Unmodified proxy methods
    public void connect(SocketAddress endpoint) throws IOException { w.connect(endpoint); }
    public void connect(SocketAddress endpoint, int timeout) throws IOException { w.connect(endpoint, timeout); }
    public void bind(SocketAddress bindpoint) throws IOException { w.bind(bindpoint); }
    public InetAddress getInetAddress() { return w.getInetAddress(); }
    public InetAddress getLocalAddress() { return w.getLocalAddress(); }
    public int getPort() { return w.getPort(); }
    public int getLocalPort() { return w.getLocalPort(); }
    public SocketAddress getRemoteSocketAddress() { return w.getRemoteSocketAddress(); }
    public SocketAddress getLocalSocketAddress() { return w.getLocalSocketAddress(); }
    //public SocketChannel getChannel() { return w.getChannel(); }
    public InputStream getInputStream() throws IOException { return w.getInputStream(); }
    //public OutputStream getOutputStream() throws IOException { return w.getOutputStream(); }
    public void setTcpNoDelay(boolean on) throws SocketException { w.setTcpNoDelay(on); }
    public boolean getTcpNoDelay() throws SocketException { return w.getTcpNoDelay(); }
    public void setSoLinger(boolean on, int linger) throws SocketException { w.setSoLinger(on, linger); } 
    public int getSoLinger() throws SocketException { return w.getSoLinger(); }
    public void sendUrgentData(int data) throws IOException  { w.sendUrgentData(data); }
    public void setOOBInline(boolean on) throws SocketException { w.setOOBInline(on); }
    public boolean getOOBInline() throws SocketException { return w.getOOBInline(); }
    public void setSoTimeout(int timeout) throws SocketException { w.setSoTimeout(timeout); }
    public int getSoTimeout() throws SocketException { return w.getSoTimeout(); }
    public void setSendBufferSize(int size) throws SocketException { w.setSendBufferSize(size); }
    public int getSendBufferSize() throws SocketException { return w.getSendBufferSize(); }
    public void setReceiveBufferSize(int size) throws SocketException  { w.setReceiveBufferSize(size); }
    public int getReceiveBufferSize() throws SocketException { return w.getReceiveBufferSize(); }
    public void setKeepAlive(boolean on) throws SocketException { w.setKeepAlive(on); }
    public boolean getKeepAlive() throws SocketException { return w.getKeepAlive(); }
    public void setTrafficClass(int tc) throws SocketException { w.setTrafficClass(tc); }
    public int getTrafficClass() throws SocketException { return w.getTrafficClass(); }
    public void setReuseAddress(boolean on) throws SocketException { w.setReuseAddress(on); }
    public boolean getReuseAddress() throws SocketException { return w.getReuseAddress(); }
    //public void close() throws IOException { w.close(); }
    public void shutdownInput() throws IOException { w.shutdownInput(); }
    public void shutdownOutput() throws IOException { w.shutdownOutput(); }
    public String toString() { return w.toString(); }
    public boolean isConnected() { return w.isConnected(); }
    public boolean isBound() { return w.isBound(); }
    public boolean isClosed() { return w.isClosed(); }
    public boolean isInputShutdown() { return w.isInputShutdown(); }
    public boolean isOutputShutdown() { return w.isOutputShutdown(); }
    public void setPerformancePreferences(int connectionTime, int latency, int bandwidth) { w.setPerformancePreferences(connectionTime, latency, bandwidth); }
  }
  public static class InputStreamWrapper extends InputStream {
    protected InputStream w;
    protected int id;
    public InputStreamWrapper(InputStream r, int i) throws IOException { w = r; id = i; }
    public int read() throws IOException { return w.read(); }
    public int read(byte[] b) throws IOException { return w.read(b); }
    public boolean inRead = false;
    public int read(byte[] b, int off, int len) throws IOException {
      inRead = true;
      if(finalizePaused) {
        System.out.println("Finished sending request. Resuming paused finalizer before reading response...");
        synchronized(signal) { signal.notifyAll(); }
        // Let finalizer run before continuing
        try { Thread.sleep(1000); } catch(InterruptedException e) {}
      }
      try {
        return w.read(b, off, len);
      } finally {
        inRead = false;
      }
    }
    public long skip(long n) throws IOException { return w.skip(n); }
    public int available() throws IOException {
      System.out.println("MeteredStream/KeepAliveStream is being finalized, HttpsClient will be added back to the cache shortly");
      returnedToCache = true;
      return w.available();
    }
    public void close() throws IOException { w.close(); }
    public void mark(int readlimit) { w.mark(readlimit); }
    public void reset() throws IOException { w.reset(); }
    public boolean markSupported() { return w.markSupported(); }
  }
  public static class SSLSocketWrapper extends SSLSocket {
    protected SSLSocket w;
    protected int id;
    public SSLSocketWrapper(SSLSocket r, int i) { w = r; id = i; }
    SocketWrapper s;
    public void connect(SocketAddress endpoint) throws IOException {
      System.out.println("Connecting SSLSocket #"+id);
      InetSocketAddress a = (InetSocketAddress)endpoint;
      s = new SocketWrapper(new Socket(a.getHostString(), a.getPort()), id);
      w = (SSLSocket)SSLSocketFactoryWrapper.w.createSocket(s, a.getHostString(), a.getPort(), true);
    }
    public InputStream getInputStream() throws IOException {
      InputStreamWrapper is = new InputStreamWrapper(w.getInputStream(), id);
      s.is = is;
      return is;
    }
    // Unmodified proxy methods
    //public void connect(SocketAddress endpoint) throws IOException { w.connect(endpoint); }
    public void connect(SocketAddress endpoint, int timeout) throws IOException { w.connect(endpoint, timeout); }
    public void bind(SocketAddress bindpoint) throws IOException { w.bind(bindpoint); }
    public InetAddress getInetAddress() { return w.getInetAddress(); }
    public InetAddress getLocalAddress() { return w.getLocalAddress(); }
    public int getPort() { return w.getPort(); }
    public int getLocalPort() { return w.getLocalPort(); }
    public SocketAddress getRemoteSocketAddress() { return w.getRemoteSocketAddress(); }
    public SocketAddress getLocalSocketAddress() { return w.getLocalSocketAddress(); }
    //public SocketChannel getChannel() { return w.getChannel(); }
    //public InputStream getInputStream() throws IOException { return w.getInputStream(); }
    public OutputStream getOutputStream() throws IOException { return w.getOutputStream(); }
    public void setTcpNoDelay(boolean on) throws SocketException { w.setTcpNoDelay(on); }
    public boolean getTcpNoDelay() throws SocketException { return w.getTcpNoDelay(); }
    public void setSoLinger(boolean on, int linger) throws SocketException { w.setSoLinger(on, linger); } 
    public int getSoLinger() throws SocketException { return w.getSoLinger(); }
    public void sendUrgentData(int data) throws IOException  { w.sendUrgentData(data); }
    public void setOOBInline(boolean on) throws SocketException { w.setOOBInline(on); }
    public boolean getOOBInline() throws SocketException { return w.getOOBInline(); }
    public void setSoTimeout(int timeout) throws SocketException { w.setSoTimeout(timeout); }
    public int getSoTimeout() throws SocketException { return w.getSoTimeout(); }
    public void setSendBufferSize(int size) throws SocketException { w.setSendBufferSize(size); }
    public int getSendBufferSize() throws SocketException { return w.getSendBufferSize(); }
    public void setReceiveBufferSize(int size) throws SocketException  { w.setReceiveBufferSize(size); }
    public int getReceiveBufferSize() throws SocketException { return w.getReceiveBufferSize(); }
    public void setKeepAlive(boolean on) throws SocketException { w.setKeepAlive(on); }
    public boolean getKeepAlive() throws SocketException { return w.getKeepAlive(); }
    public void setTrafficClass(int tc) throws SocketException { w.setTrafficClass(tc); }
    public int getTrafficClass() throws SocketException { return w.getTrafficClass(); }
    public void setReuseAddress(boolean on) throws SocketException { w.setReuseAddress(on); }
    public boolean getReuseAddress() throws SocketException { return w.getReuseAddress(); }
    public void close() throws IOException { w.close(); }
    public void shutdownInput() throws IOException { w.shutdownInput(); }
    public void shutdownOutput() throws IOException { w.shutdownOutput(); }
    public String toString() { return w.toString(); }
    public boolean isConnected() { return w.isConnected(); }
    public boolean isBound() { return w.isBound(); }
    public boolean isClosed() { return w.isClosed(); }
    public boolean isInputShutdown() { return w.isInputShutdown(); }
    public boolean isOutputShutdown() { return w.isOutputShutdown(); }
    public void setPerformancePreferences(int connectionTime, int latency, int bandwidth) { w.setPerformancePreferences(connectionTime, latency, bandwidth); }
    public String[] getSupportedCipherSuites() { return w.getSupportedCipherSuites(); }
    public String[] getEnabledCipherSuites() { return w.getEnabledCipherSuites(); }
    public void setEnabledCipherSuites(String[] suites) { w.setEnabledCipherSuites(suites); }
    public String[] getSupportedProtocols() { return w.getSupportedProtocols(); }
    public String[] getEnabledProtocols() { return w.getEnabledProtocols(); }
    public void setEnabledProtocols(String[] protocols) { w.setEnabledProtocols(protocols); }
    public SSLSession getSession() { return w.getSession(); }
    public SSLSession getHandshakeSession() { return w.getHandshakeSession(); }
    public void addHandshakeCompletedListener(HandshakeCompletedListener listener) { w.addHandshakeCompletedListener(listener); }
    public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) { w.removeHandshakeCompletedListener(listener); }
    public void startHandshake() throws IOException { w.startHandshake(); }
    public void setUseClientMode(boolean mode) { w.setUseClientMode(mode); }
    public boolean getUseClientMode() { return w.getUseClientMode(); }
    public void setNeedClientAuth(boolean need) { w.setNeedClientAuth(need); }
    public boolean getNeedClientAuth() { return w.getNeedClientAuth(); }
    public void setWantClientAuth(boolean want) { w.setWantClientAuth(want); }
    public boolean getWantClientAuth() { return w.getWantClientAuth(); }
    public void setEnableSessionCreation(boolean flag) { w.setEnableSessionCreation(flag); }
    public boolean getEnableSessionCreation() { return w.getEnableSessionCreation(); }
    public SSLParameters getSSLParameters() { return w.getSSLParameters(); }
    public void setSSLParameters(SSLParameters params) { w.setSSLParameters(params); }
  }
  public static int sock_count = 0;
  public static class SSLSocketFactoryWrapper extends SSLSocketFactory {
    public static SSLSocketFactory w;
    public SSLSocketFactoryWrapper(SSLSocketFactory r) { w = r; }
    public Socket createSocket() throws IOException {
      sock_count++;
      return new SSLSocketWrapper((SSLSocket)w.createSocket(), sock_count);
    }
    public String[] getSupportedCipherSuites() { return w.getSupportedCipherSuites(); }
    public String[] getDefaultCipherSuites() { return w.getDefaultCipherSuites(); }
    // Not used, but must be implemented anyway
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException { return null; }
    public Socket createSocket(InetAddress host, int port) throws IOException { return null; }
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException { return null; }
    public Socket createSocket(String host, int port) throws IOException, UnknownHostException { return null; }
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException, UnknownHostException { return null; }
  }
}
