/*
 * Copyright (c) 1996, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.net.www.http;

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

import jdk.internal.misc.InnocuousThread;
import sun.net.www.MeteredStream;
import sun.security.action.GetIntegerAction;

/**
 * A class that implements a cache of active/idle Http connections for keep-alive
 *
 * @author Stephen R. Pietrowicz (NCSA)
 * @author Dave Brown
 * @author Paul Donohue
 */
public class KeepAliveCache {
    protected ActiveKeepAliveCache active = new ActiveKeepAliveCache();
    protected IdleKeepAliveCache idle = new IdleKeepAliveCache();

    public KeepAliveCache() {}

    /**
     * Register this active HttpClient with the cache
     * @param http  The HttpClient to be cached
     * @param owner The HttpURLConnection that is currently using this HttpClient
     */
    public void putActive(HttpClient http, Object owner) {
        if (owner != null)
            active.put(http, owner);
    }

    /**
     * Register this URL and HttpClient (that supports keep-alive) as idle and
     * available for reuse
     * @param url   The URL contains info about the host and port
     * @param http  The HttpClient to be marked as idle
     */
    public void putIdle(final URL url, Object obj, HttpClient http) {
        active.remove(http);
        idle.put(url, obj, http);
    }

    /**
     * Check to see if this URL has an available idle HttpClient
     */
    public HttpClient getIdle(final URL url, Object obj, Object owner) {
        HttpClient http = idle.get(url, obj);
        if (http != null && owner != null)
            active.put(http, owner);
        return http;
    }

    /**
     * Explicitly remove this HttpClient from the cache
     */
    public void remove(HttpClient http, Object obj) {
        active.remove(http);
        idle.remove(http, obj);
    }

    /**
     * @deprecated Use putIdle() instead.
     */
    @Deprecated
    public void put(final URL url, Object obj, HttpClient http) {
        putIdle(url, obj, http);
    }

    /**
     * @deprecated Use getIdle() instead.
     */
    @Deprecated
    public HttpClient get(URL url, Object obj) {
        return getIdle(url, obj, null);
    }
}


/*
 * The Active cache is used to reclaim HttpClient objects when the associated
 * owner (HttpURLConnection) objects are garbage collected.  Without this, the
 * MeteredStream finalizer and KeepAliveStream close() method may resurrect an
 * HttpClient object after the garbage collector has started collecting it,
 * which can lead to various race conditions between the garbage collector and
 * user threads.
 */
class ActiveKeepAliveCache
    implements Runnable {

    protected ActiveKeepAliveOwnerMap owners = new ActiveKeepAliveOwnerMap();
    protected ReferenceQueue<Object> deadOwnersQ = new ReferenceQueue<Object>();
    protected ActiveKeepAliveClientMap clients = new ActiveKeepAliveClientMap();
    private Thread gcHandler = null;

    public ActiveKeepAliveCache() {}

    public synchronized void put(HttpClient http, Object owner) {
        WeakReference<Object> ownerRef = new WeakReference<Object>(owner, deadOwnersQ);
        owners.put(ownerRef, new WeakReference<HttpClient>(http));
        clients.put(http, ownerRef);

        if (gcHandler == null || !gcHandler.isAlive()) {
            final ActiveKeepAliveCache cache = this;
            AccessController.doPrivileged(new PrivilegedAction<>() {
                public Void run() {
                    gcHandler = InnocuousThread.newSystemThread("Keep-Alive-GC-Handler", cache);
                    gcHandler.setDaemon(true);
                    gcHandler.setPriority(Thread.MAX_PRIORITY - 2);
                    gcHandler.start();
                    return null;
                }
            });
        }
    }

    public synchronized void remove(HttpClient http) {
        WeakReference<Object> ownerRef = clients.remove(http);
        if (ownerRef != null) {
            owners.remove(ownerRef);
        }
    }

    @Override
    public void run() {
        while (true) {
            try {
                Reference<? extends Object> deadOwnerRef = deadOwnersQ.remove();
                HttpClient http = null;
                synchronized (this) {
                    WeakReference<HttpClient> httpRef = owners.remove(deadOwnerRef);
                    if (httpRef != null) {
                        http = httpRef.get();
                        if (http != null) {
                            WeakReference<Object> ownerRef = clients.remove(http);
                            if (ownerRef == null || ownerRef != deadOwnerRef) {
                                // Shouldn't happen, but theoretically possible
                                // if something calls put() twice with the same
                                // HttpClient but two different owners
                                http = null;
                            }
                        }
                    }
                }
                if (http != null) {
                    // KeepAliveCache.setIdle() is usually called before the
                    // owner (HttpURLConnection) is garbage collected.
                    // However, that may not happen if the user of
                    // HttpURLConnection did not either close or fully read the
                    // response InputStream.   In that case, closing it here
                    // will cause KeepAliveCache.setIdle() to be called if
                    // appropriate.
                    // Otherwise, KeepAliveCache.setIdle() was not called
                    // because the HttpClient cannot be reused and we should
                    // allow it to be garbage collected.
                    InputStream is = http.getInputStream();
                    if (is != null &&
                        (is instanceof ChunkedInputStream ||
                         is instanceof MeteredStream)) {
                        try {
                            is.close();
                        } catch (IOException e) {}
                    }
                }
            } catch (InterruptedException e) {}
            synchronized (this) {
                if (owners.isEmpty()) {
                    return;
                }
            }
        }
    }
}


class ActiveKeepAliveOwnerMap
    extends HashMap<WeakReference<Object>, WeakReference<HttpClient>> {
    @java.io.Serial
    private static final long serialVersionUID = -1516438533168901532L;

    public ActiveKeepAliveOwnerMap() {}

    /*
     * Do not serialize this class!
     */
    @java.io.Serial
    private void writeObject(ObjectOutputStream stream) throws IOException {
        throw new NotSerializableException();
    }

    @java.io.Serial
    private void readObject(ObjectInputStream stream)
        throws IOException, ClassNotFoundException
    {
        throw new NotSerializableException();
    }
}


class ActiveKeepAliveClientMap
    extends HashMap<HttpClient, WeakReference<Object>> {
    @java.io.Serial
    private static final long serialVersionUID = -2431098254089313531L;

    public ActiveKeepAliveClientMap() {}

    /*
     * Do not serialize this class!
     */
    @java.io.Serial
    private void writeObject(ObjectOutputStream stream) throws IOException {
        throw new NotSerializableException();
    }

    @java.io.Serial
    private void readObject(ObjectInputStream stream)
        throws IOException, ClassNotFoundException
    {
        throw new NotSerializableException();
    }
}


class IdleKeepAliveCache
    extends HashMap<IdleKeepAliveKey, IdleClientVector>
    implements Runnable {
    @java.io.Serial
    private static final long serialVersionUID = -2937172892064557949L;

    /* maximum # keep-alive connections to maintain at once
     * This should be 2 by the HTTP spec, but because we don't support pipe-lining
     * a larger value is more appropriate. So we now set a default of 5, and the value
     * refers to the number of idle connections per destination (in the cache) only.
     * It can be reset by setting system property "http.maxConnections".
     */
    static final int MAX_CONNECTIONS = 5;
    static int result = -1;
    static int getMaxConnections() {
        if (result == -1) {
            result = AccessController.doPrivileged(
                new GetIntegerAction("http.maxConnections", MAX_CONNECTIONS))
                .intValue();
            if (result <= 0) {
                result = MAX_CONNECTIONS;
            }
        }
        return result;
    }

    static final int LIFETIME = 5000;

    private Thread keepAliveTimer = null;

    /**
     * Constructor
     */
    public IdleKeepAliveCache() {}

    /**
     * Register this URL and HttpClient (that supports keep-alive) with the cache
     * @param url  The URL contains info about the host and port
     * @param http The HttpClient to be cached
     */
    public synchronized void put(final URL url, Object obj, HttpClient http) {
        if (keepAliveTimer == null || !keepAliveTimer.isAlive()) {
            clear();
            /* Unfortunately, we can't always believe the keep-alive timeout we got
             * back from the server.  If I'm connected through a Netscape proxy
             * to a server that sent me a keep-alive
             * time of 15 sec, the proxy unilaterally terminates my connection
             * The robustness to get around this is in HttpClient.parseHTTP()
             */
            final IdleKeepAliveCache cache = this;
            AccessController.doPrivileged(new PrivilegedAction<>() {
                public Void run() {
                    keepAliveTimer = InnocuousThread.newSystemThread("Keep-Alive-Timer", cache);
                    keepAliveTimer.setDaemon(true);
                    keepAliveTimer.setPriority(Thread.MAX_PRIORITY - 2);
                    keepAliveTimer.start();
                    return null;
                }
            });
        }

        IdleKeepAliveKey key = new IdleKeepAliveKey(url, obj);
        IdleClientVector v = super.get(key);

        if (v == null) {
            int keepAliveTimeout = http.getKeepAliveTimeout();
            v = new IdleClientVector(keepAliveTimeout > 0 ?
                                 keepAliveTimeout * 1000 : LIFETIME);
            v.put(http);
            super.put(key, v);
        } else {
            v.put(http);
        }
    }

    /* remove an obsolete HttpClient from its VectorCache */
    public synchronized void remove(HttpClient h, Object obj) {
        IdleKeepAliveKey key = new IdleKeepAliveKey(h.url, obj);
        IdleClientVector v = super.get(key);
        if (v != null) {
            v.remove(h);
            if (v.isEmpty()) {
                removeVector(key);
            }
        }
    }

    /* called by a clientVector thread when all its connections have timed out
     * and that vector of connections should be removed.
     */
    synchronized void removeVector(IdleKeepAliveKey k) {
        super.remove(k);
    }

    /**
     * Check to see if this URL has a cached HttpClient
     */
    public synchronized HttpClient get(URL url, Object obj) {
        IdleKeepAliveKey key = new IdleKeepAliveKey(url, obj);
        IdleClientVector v = super.get(key);
        if (v == null) { // nothing in cache yet
            return null;
        }
        return v.get();
    }

    /* Sleeps for an allotted timeout, then checks for timed out connections.
     * Errs on the side of caution (leave connections idle for a relatively
     * short time).
     */
    @Override
    public void run() {
        do {
            try {
                Thread.sleep(LIFETIME);
            } catch (InterruptedException e) {}

            // Remove all outdated HttpClients.
            synchronized (this) {
                long currentTime = System.currentTimeMillis();
                List<IdleKeepAliveKey> keysToRemove = new ArrayList<>();

                for (IdleKeepAliveKey key : keySet()) {
                    IdleClientVector v = get(key);
                    synchronized (v) {
                        IdleKeepAliveEntry e = v.peek();
                        while (e != null) {
                            if ((currentTime - e.idleStartTime) > v.nap) {
                                v.poll();
                                e.hc.closeServer();
                            } else {
                                break;
                            }
                            e = v.peek();
                        }

                        if (v.isEmpty()) {
                            keysToRemove.add(key);
                        }
                    }
                }

                for (IdleKeepAliveKey key : keysToRemove) {
                    removeVector(key);
                }
            }
        } while (!isEmpty());
    }

    /*
     * Do not serialize this class!
     */
    @java.io.Serial
    private void writeObject(ObjectOutputStream stream) throws IOException {
        throw new NotSerializableException();
    }

    @java.io.Serial
    private void readObject(ObjectInputStream stream)
        throws IOException, ClassNotFoundException
    {
        throw new NotSerializableException();
    }
}

/* FILO order for recycling HttpClients, should run in a thread
 * to time them out.  If > maxConns are in use, block.
 */
class IdleClientVector extends ArrayDeque<IdleKeepAliveEntry> {
    @java.io.Serial
    private static final long serialVersionUID = -8680532108106489459L;

    // sleep time in milliseconds, before cache clear
    int nap;

    IdleClientVector(int nap) {
        this.nap = nap;
    }

    synchronized HttpClient get() {
        if (isEmpty()) {
            return null;
        }

        // Loop until we find a connection that has not timed out
        HttpClient hc = null;
        long currentTime = System.currentTimeMillis();
        do {
            IdleKeepAliveEntry e = pop();
            if ((currentTime - e.idleStartTime) > nap) {
                e.hc.closeServer();
            } else {
                hc = e.hc;
            }
        } while ((hc == null) && (!isEmpty()));
        return hc;
    }

    /* return a still valid, unused HttpClient */
    synchronized void put(HttpClient h) {
        if (size() >= IdleKeepAliveCache.getMaxConnections()) {
            h.closeServer(); // otherwise the connection remains in limbo
        } else {
            push(new IdleKeepAliveEntry(h, System.currentTimeMillis()));
        }
    }

    /* remove an HttpClient */
    synchronized boolean remove(HttpClient h) {
        for (IdleKeepAliveEntry curr : this) {
            if (curr.hc == h) {
                return super.remove(curr);
            }
        }
        return false;
    }

    /*
     * Do not serialize this class!
     */
    @java.io.Serial
    private void writeObject(ObjectOutputStream stream) throws IOException {
        throw new NotSerializableException();
    }

    @java.io.Serial
    private void readObject(ObjectInputStream stream)
        throws IOException, ClassNotFoundException
    {
        throw new NotSerializableException();
    }
}

class IdleKeepAliveKey {
    private String      protocol = null;
    private String      host = null;
    private int         port = 0;
    private Object      obj = null; // additional key, such as socketfactory

    /**
     * Constructor
     *
     * @param url the URL containing the protocol, host and port information
     */
    public IdleKeepAliveKey(URL url, Object obj) {
        this.protocol = url.getProtocol();
        this.host = url.getHost();
        this.port = url.getPort();
        this.obj = obj;
    }

    /**
     * Determine whether or not two objects of this type are equal
     */
    @Override
    public boolean equals(Object obj) {
        if ((obj instanceof IdleKeepAliveKey) == false)
            return false;
        IdleKeepAliveKey kae = (IdleKeepAliveKey)obj;
        return host.equals(kae.host)
            && (port == kae.port)
            && protocol.equals(kae.protocol)
            && this.obj == kae.obj;
    }

    /**
     * The hashCode() for this object is the string hashCode() of
     * concatenation of the protocol, host name and port.
     */
    @Override
    public int hashCode() {
        String str = protocol+host+port;
        return this.obj == null? str.hashCode() :
            str.hashCode() + this.obj.hashCode();
    }
}

class IdleKeepAliveEntry {
    HttpClient hc;
    long idleStartTime;

    IdleKeepAliveEntry(HttpClient hc, long idleStartTime) {
        this.hc = hc;
        this.idleStartTime = idleStartTime;
    }
}
