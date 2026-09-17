package fr.regie.patch;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Balayage du sous-réseau : ICMP quand le système l'autorise, sinon tentative
 * de connexion TCP sur les ports habituels d'un réseau de plateau.
 */
public class Scanner {

    public static class Hote {
        public String ip, nom = "", role = "";
        public double ms;
        public boolean ok = true;
    }

    public final List<Hote> hotes = new CopyOnWriteArrayList<Hote>();
    public final AtomicInteger faits = new AtomicInteger();
    public volatile int total = 254;
    public volatile boolean encours;

    private static final int[] PORTS = { 80, 443, 22, 8080 };
    private ExecutorService piscine;

    public void lancer(final String base) {
        if (encours || base == null || base.isEmpty()) return;
        hotes.clear(); faits.set(0); encours = true;
        piscine = Executors.newFixedThreadPool(48);
        for (int i = 1; i <= 254; i++) {
            final String ip = base + "." + i;
            piscine.execute(new Runnable() {
                public void run() {
                    try { sonder(ip); } catch (Exception ignore) { }
                    finally {
                        if (faits.incrementAndGet() >= 254) { encours = false; piscine.shutdown(); }
                    }
                }
            });
        }
    }

    private void sonder(String ip) throws Exception {
        long t0 = System.nanoTime();
        InetAddress a = InetAddress.getByName(ip);
        boolean vivant = false;
        try { vivant = a.isReachable(500); } catch (Exception ignore) { }
        if (!vivant) {
            for (int p : PORTS) {
                Socket s = new Socket();
                try {
                    s.connect(new InetSocketAddress(a, p), 350);
                    vivant = true;
                } catch (Exception ignore) {
                } finally { try { s.close(); } catch (Exception ignore) { } }
                if (vivant) break;
            }
        }
        if (!vivant) return;
        Hote h = new Hote();
        h.ip = ip;
        h.ms = (System.nanoTime() - t0) / 1e6;
        hotes.add(h);
    }

    public void arreter() {
        encours = false;
        if (piscine != null) piscine.shutdownNow();
    }
}
