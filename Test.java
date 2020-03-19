import java.io.*;
import java.net.*;
import javax.net.ssl.*;
import java.util.*;
import java.lang.instrument.*;
import java.security.ProtectionDomain;
import javassist.*;

public class Test {

  public static Object signal = new Object();
  public static int clientPort, serverPort;
  public static boolean returnedToCache;
  public static boolean finalizePaused;
  public static boolean waitingForResponse;

  public static void main(String[] args) {
    try {

      // Start a mock HTTPS server
      SSLServerSocketFactory sssf = (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
      SSLServerSocket sss = (SSLServerSocket) sssf.createServerSocket(0);
      String host = sss.getInetAddress().toString();
      if(host.equals("0.0.0.0/0.0.0.0")) { host = "localhost"; }
      serverPort = sss.getLocalPort();
      String url = "https://"+host+":"+serverPort+"/";
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

      // GC typically finalizes the MeteredStream/KeepAliveStream then the SSLSocket then the
      // underlying SocketImpl.  However, this order is not guaranteed, so loop until finalization
      // happens in that order.
      int i;
      for(i=0; i<5; i++) {
        returnedToCache = false;
        finalizePaused = false;

        // Make a request to the server (create an HttpsClient that will be reused)
        doRequest(url);

        // Trigger a garbage collection
        System.out.println("TEST: Triggering GC and pausing main thread...");
        System.gc();

        // Wait for the SSLSocket finalizer to be called.  Our finalize() hook will run in the GC
        // thread, wake this main thread, and then pause the GC thread before running the SSLSocket
        // finalizer to force operations to occur in a specific order that would otherwise only
        // occur on rare occasions based on the relative timing of the main and GC threads.
        synchronized(signal) { signal.wait(1000); }
        System.out.println("TEST: ... Main thread resumed after wake or timeout.");

        // Verify that GC called the finalizers in the expected order
        if(!returnedToCache) {
          System.out.println("TEST: SSLSocket was finalized before MeteredStream/KeepAliveStream was finalized and HttpsClient was added to KeepAliveCache. Starting over.");
          // Let GC finish before trying again
          try { Thread.sleep(100); } catch(InterruptedException e) {}
          continue;
        }
        if(!finalizePaused) {
          System.out.println("TEST: GC didn't finalize the SSLSocket? Trying again.");
          System.gc();
          synchronized(signal) { signal.wait(1000); }
          if(!finalizePaused) {
            System.out.println("TEST: GC still didn't finalize the SSLSocket? Starting over.");
            continue;
          }
        }
        System.out.println("TEST: GC finalization appears to have been performed in the expected order. Attempting to reproduce the bug.");
        break;
      }
      if(i == 5) {
        System.out.println("TEST: Garbage collector did not collect in expected order within 5 tries. Giving up.");
        System.exit(1);
      }
      waitingForResponse = false;

      // Make another request to the server.  This should re-use the existing HttpsClient.
      //
      // Note that the GC thread is still paused at the beginning of the SSLSocket finalizer.  After
      // sending the request, our parseHTTPHeader() hook will resume the GC thread (forcing
      // operations to occur in a specific order that would otherwise only occur on rare occasions
      // based on the relative timing of the main and GC threads), and the SSLSocket finalizer in
      // the GC thread will cause the connection te be terminated, demonstrating that there is a bug
      // in the connection reuse behavior of HttpsClient.  Our close() hook will call System.exit()
      // to stop this test, so doRequest() should not return if the bug has been reproduced.
      //
      // If System.exit() were not called to stop this test then Java would normally retry the
      // request automatically.  This would mask the bug in simple cases, but can cause problems if
      // the request is non-idempotent or if another bug like the following is triggered by the
      // retry: https://bugs.openjdk.java.net/browse/JDK-8209178
      doRequest(url);

      System.out.println("TEST: System.exit() was not called when it was expected. This test seems to have failed to reproduce the bug.");
      System.exit(1);
    } catch (Exception e) {
      System.out.println("TEST: Exception: "+e);
      e.printStackTrace();
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
    // Fully reading the response body causes the HttpsClient to be added to the KeepAliveCache
    // immediately, which avoids this bug since GC will not finalize the SSLSocket around the same
    // time as the HttpsClient is added back to the KeepAliveCache.  However, the HttpsURLConnection
    // API does not require the body to be read, and there are cases where API users might not have
    // any need to read the body (such as when reading the Location header from a 201 response and
    // ignoring the body).
    //InputStream is = c.getInputStream();
    //while(is.skip(1000) > 0 && is.read() != -1) {}
    //is.close();
  }



  // Hooks used to identify the execution of relevant operations and to induce a specific order of
  // operations that would otherwise only occur on rare occasions based on the relative timing of
  // the main and GC threads.

  public static void premain(String agentArgs, Instrumentation inst) {
    for (ClassHook h: hooks) {
      inst.addTransformer(h, true);
      /*
      // This should not be needed in premain() and should only be needed if using agentmain().
      try {
        Class<?> targetClass = Class.forName(h.targetClassName);
        try {
          inst.retransformClasses(targetClass);
        } catch (UnmodifiableClassException e) {
          System.out.println("TEST: Exception: "+e);
          e.printStackTrace();
        }
      } catch (Exception e) {}
      */
    }
  }

  public static abstract class ClassHook implements ClassFileTransformer {
    public final String targetClassName;
    public final String transformClassName;

    public ClassHook(String targetClassName) {
      this.targetClassName = targetClassName;
      transformClassName = targetClassName.replace('.', '/');
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
     ProtectionDomain protectionDomain, byte[] classfileBuffer) {
      if(!className.equals(transformClassName)) {
        return classfileBuffer;
      }
      ClassPool cp = ClassPool.getDefault();
      try {
        CtClass cc = cp.get(targetClassName);
        injectMethodHooks(cc);
        byte[] byteCode = cc.toBytecode();
        cc.detach();
        return byteCode;
      //} catch (NotFoundException | CannotCompileException | IOException e) {
      } catch (Throwable e) {
        System.out.println("TEST: Hook setup failed for \""+targetClassName+"\": "+e);
        e.printStackTrace();
        System.exit(1);
        return null;
      }
    }

    public final String injectPrefix = "try { \n";
    public final String injectSuffix = "} catch(Throwable e) { System.out.println(\"TEST: Exception in injected code: \"+e); }";
    public abstract void injectMethodHooks(CtClass cc) throws NotFoundException, CannotCompileException;
  }

  static List<ClassHook> hooks = new ArrayList<ClassHook>(Arrays.asList(
    new HttpClientHook(),
    new SSLSocketHook(),
    new IBMSSLSocketHook(),
    new SocketImplHook(),
    new SocketHook()
  ));

  public static class HttpClientHook extends ClassHook {
    public HttpClientHook() {
      super("sun.net.www.http.HttpClient");
    }
    @Override
    public void injectMethodHooks(CtClass cc) throws NotFoundException, CannotCompileException {
      CtMethod m;
      String code;
      m = cc.getDeclaredMethod("openServer");
      code = injectPrefix +
       "System.out.println(\"TEST: A new HttpsClient has been created.\");\n" +
       "Class test = ClassLoader.getSystemClassLoader().loadClass(\"Test\");\n" +
       "test.getField(\"clientPort\").setInt(null, serverSocket.getLocalPort());\n" +
       injectSuffix;
      m.insertAfter(code);
      m = cc.getDeclaredMethod("parseHTTPHeader");
      code = injectPrefix +
       "Class test = ClassLoader.getSystemClassLoader().loadClass(\"Test\");\n" +
       "if(serverSocket.getPort() == test.getField(\"serverPort\").getInt(null) && serverSocket.getLocalPort() == test.getField(\"clientPort\").getInt(null)) {\n" +
       "  System.out.println(\"TEST: HttpsClient finished request and is waiting for response.\");\n" +
       "  test.getField(\"waitingForResponse\").setBoolean(null, true);\n" +
       "  if(test.getField(\"finalizePaused\").getBoolean(null)) {\n" +
       "    System.out.println(\"TEST: Waking GC thread and pausing main thread...\");\n" +
       "    Object signal = test.getField(\"signal\").get(null);\n" +
       "    synchronized(signal) { signal.notifyAll(); }\n" +
       "    try { Thread.sleep((long)1000); } catch(InterruptedException e) {}\n" +
       "    System.out.println(\"TEST: ... Main thread resumed after timeout.\");\n" +
       "  }\n" +
       "}\n" +
       injectSuffix;
      m.insertBefore(code);
      code = injectPrefix +
       "Class test = ClassLoader.getSystemClassLoader().loadClass(\"Test\");\n" +
       "if(serverSocket.getPort() == test.getField(\"serverPort\").getInt(null) && serverSocket.getLocalPort() == test.getField(\"clientPort\").getInt(null)) {\n" +
       "  System.out.println(\"TEST: HttpsClient got response.\");\n" +
       "  Class test = ClassLoader.getSystemClassLoader().loadClass(\"Test\");\n" +
       "  test.getField(\"waitingForResponse\").setBoolean(null, false);\n" +
       "}\n" +
       injectSuffix;
      m.insertAfter(code);
      m = cc.getDeclaredMethod("finished");
      code = injectPrefix +
       "Class test = ClassLoader.getSystemClassLoader().loadClass(\"Test\");\n" +
       "if(serverSocket.getPort() == test.getField(\"serverPort\").getInt(null) && serverSocket.getLocalPort() == test.getField(\"clientPort\").getInt(null)) {\n" +
       "  System.out.println(\"TEST: MeteredStream/KeepAliveStream has been finalized and HttpsClient has been added to the KeepAliveCache.\");\n" +
       "  Class test = ClassLoader.getSystemClassLoader().loadClass(\"Test\");\n" +
       "  test.getField(\"returnedToCache\").setBoolean(null, true);\n" +
       "}\n" +
       injectSuffix;
      m.insertAfter(code);
    }
  }

  public static class SSLSocketHook extends ClassHook {
    public SSLSocketHook() {
      super("sun.security.ssl.BaseSSLSocketImpl");
    }
    public SSLSocketHook(String targetClassName) {
      super(targetClassName);
    }
    @Override
    public void injectMethodHooks(CtClass cc) throws NotFoundException, CannotCompileException {
      CtMethod m = cc.getDeclaredMethod("finalize");
      String code = injectPrefix +
       "Class test = ClassLoader.getSystemClassLoader().loadClass(\"Test\");\n" +
       "if(getPort() == test.getField(\"serverPort\").getInt(null) && getLocalPort() == test.getField(\"clientPort\").getInt(null)) {\n" +
       "  if(test.getField(\"returnedToCache\").getBoolean(null)) {\n" +
       "    System.out.println(\"TEST: SSLSocket.finalize() called. Waking main thread and pausing GC thread...\");\n" +
       "    test.getField(\"finalizePaused\").setBoolean(null, true);\n" +
       "    Object signal = test.getField(\"signal\").get(null);\n" +
       "    synchronized(signal) {\n" +
       "      signal.notifyAll();\n" +
       "      try { signal.wait((long)5000); } catch(Exception e) {}\n" +
       "    }\n" +
       "    System.out.println(\"TEST: ... GC thread resumed after wake or timeout.\");\n" +
       "  } else {\n" +
       "    System.out.println(\"TEST: SSLSocket.finalize() called at the wrong time. Finishing GC so we can start over.\");\n" +
       "  }\n" +
       "}\n" +
       injectSuffix;
      m.insertBefore(code);
    }
  }

  // For IBM JVM, which uses a different SSLSocket implementation than OpenJDK
  public static class IBMSSLSocketHook extends SSLSocketHook {
    public IBMSSLSocketHook() {
      super("com.ibm.jsse2.au");
    }
  }

  public static class SocketImplHook extends ClassHook {
    public SocketImplHook() {
      super("java.net.AbstractPlainSocketImpl");
    }
    @Override
    public void injectMethodHooks(CtClass cc) throws NotFoundException, CannotCompileException {
      CtMethod m = cc.getDeclaredMethod("finalize");
      String code = injectPrefix +
       "Class test = ClassLoader.getSystemClassLoader().loadClass(\"Test\");\n" +
       "if(getPort() == test.getField(\"serverPort\").getInt(null) && getLocalPort() == test.getField(\"clientPort\").getInt(null)) {\n" +
       "  System.out.println(\"TEST: SocketImpl.finalize() called. Skipping execution of this finalizer as it does not reliably run before SSLSocketImpl.finalize() and premature execution of it will invalidate the test by closing the underlying Socket early.\");\n" +
       "  return;\n" +
       "}\n" +
       injectSuffix;
      m.insertBefore(code);
    }
  }

  public static class SocketHook extends ClassHook {
    public SocketHook() {
      super("java.net.Socket");
    }
    @Override
    public void injectMethodHooks(CtClass cc) throws NotFoundException, CannotCompileException {
      CtMethod m = cc.getDeclaredMethod("close");
      String code = injectPrefix +
       "Class test = ClassLoader.getSystemClassLoader().loadClass(\"Test\");\n" +
       "if(getPort() == test.getField(\"serverPort\").getInt(null) && getLocalPort() == test.getField(\"clientPort\").getInt(null)) {\n" +
       "  if(test.getField(\"waitingForResponse\").getBoolean(null)) {\n" +
       "    System.out.println(\"TEST: Bug has been successfully reproduced: Underlying Socket is being closed by GC thread while connection is being actively used in main thread. Exiting JVM.\");\n" +
       "    System.exit(0);\n" +
       "  } else {\n" +
       "    System.out.println(\"TEST: Socket is being closed by either SSLSocket.finalize() or an unexpected code path. Waking all threads in order to finish this attempt and start over.\");\n" +
       "    Thread.dumpStack();\n" +
       "    Object signal = test.getField(\"signal\").get(null);\n" +
       "    synchronized(signal) { signal.notifyAll(); }\n" +
       "  }\n" +
       "}\n" +
       injectSuffix;
      m.insertBefore(code);
    }
  }

}
