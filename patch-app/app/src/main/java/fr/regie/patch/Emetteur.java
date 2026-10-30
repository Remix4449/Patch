package fr.regie.patch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Émission continue : la télécommande pose des univers, ce fil les répète.
 *
 * Un gradateur ne garde pas un niveau reçu une seule fois — les récepteurs
 * sACN relâchent l'univers après quelques secondes de silence, et un nœud
 * Art-Net fait de même. La télécommande doit donc tenir le flux tant qu'elle
 * affiche un niveau : 30 trames par seconde, ce que fait n'importe quel
 * pupitre.
 */
public class Emetteur {

    private static final int PERIODE = 33;          // ms, soit ~30 Hz

    private final ArtNet art;
    private final Sacn sacn;

    /** Univers (base 1) → 512 niveaux. Remplacée d'un bloc par l'interface. */
    private final Map<Integer, byte[]> trames = new ConcurrentHashMap<Integer, byte[]>();

    public volatile String proto = "sACN";
    public volatile int prio = 100;
    public volatile String cible = "";
    public volatile boolean actif;
    public volatile long envois, echecs;

    private volatile Thread boucle;

    public Emetteur(ArtNet a, Sacn s) { art = a; sacn = s; }

    public synchronized void regler(String protocole, int priorite, String destination) {
        String p = (protocole != null && protocole.toLowerCase().startsWith("a")) ? "Art-Net" : "sACN";
        String c = destination == null ? "" : destination.trim();
        // Changer de protocole ou de destination en cours d'émission : l'ancien
        // flux est clos proprement, sinon le nœud garde deux sources qui se
        // disputent l'univers jusqu'à ce que la première expire.
        // On bascule d'abord, pour que le fil d'émission n'intercale plus de
        // trame sur l'ancienne route entre les trames de fin.
        String avantProto = proto, avantCible = cible;
        proto = p;
        prio = Math.max(0, Math.min(200, priorite <= 0 ? 100 : priorite));
        cible = c;
        if (actif && (!p.equals(avantProto) || !c.equals(avantCible)))
            relacher(new ArrayList<Integer>(trames.keySet()), avantProto, avantCible);
    }

    public synchronized void demarrer() {
        // Un arrêt suivi aussitôt d'un départ laissait l'ancien fil reprendre à
        // son réveil : deux fils émettaient alors les mêmes univers, avec des
        // numéros de séquence entremêlés que les nœuds rejettent par paquets.
        if (actif && boucle != null && boucle.isAlive()) return;
        actif = true;
        boucle = new Thread(new Runnable() { public void run() { tourner(); } }, "emission");
        boucle.setDaemon(true);
        boucle.start();
    }

    /**
     * Remplace l'ensemble des univers émis. Ceux qui disparaissent sont
     * relâchés proprement, sinon les projecteurs resteraient allumés sur la
     * dernière valeur reçue.
     */
    public synchronized void poser(Map<Integer, byte[]> nouvelles) {
        // Retirer l'univers avant de le relâcher : le fil d'émission ne doit plus
        // intercaler l'ancienne trame entre les trames à zéro.
        List<Integer> partis = new ArrayList<Integer>();
        for (Integer u : new HashMap<Integer, byte[]>(trames).keySet())
            if (!nouvelles.containsKey(u)) { trames.remove(u); partis.add(u); }
        relacher(partis, proto, cible);
        for (Map.Entry<Integer, byte[]> e : nouvelles.entrySet())
            trames.put(e.getKey(), e.getValue());
        if (!nouvelles.isEmpty()) demarrer();
    }

    /** Relâche tous les univers et arrête le fil. */
    public synchronized void arreter() {
        actif = false;
        Thread b = boucle;
        if (b != null) b.interrupt();
        List<Integer> us = new ArrayList<Integer>(trames.keySet());
        trames.clear();
        relacher(us, proto, cible);
    }

    /**
     * Trois trames à zéro, puis en sACN trois trames de fin de flux. Les zéros
     * partent d'abord en trames ordinaires : un récepteur ignore les niveaux
     * d'une trame de fin (E1.31, 6.2.6) et garde souvent la dernière image
     * reçue, si bien que l'appareil mis en évidence restait allumé.
     */
    private void relacher(List<Integer> us, String proto, String cible) {
        if (us.isEmpty()) return;
        byte[] zero = new byte[512];
        boolean an = "Art-Net".equals(proto);
        for (int i = 0; i < (an ? 3 : 6); i++) {
            for (int u : us) {
                if (an) art.emettre(u - 1, zero, cible);
                else sacn.emettre(u, zero, prio, cible, i >= 3);
            }
            try { Thread.sleep(8); } catch (InterruptedException e) { return; }
        }
    }

    private void tourner() {
        while (actif && boucle == Thread.currentThread()) {
            long t = System.currentTimeMillis();
            // Sous le verrou de poser() : une trame lue juste avant la relâche de
            // son univers partait parfois après les zéros, et le nœud la gardait.
            synchronized (this) {
                for (Map.Entry<Integer, byte[]> e : trames.entrySet()) {
                    boolean ok = "Art-Net".equals(proto)
                            ? art.emettre(e.getKey() - 1, e.getValue(), cible)
                            : sacn.emettre(e.getKey(), e.getValue(), prio, cible, false);
                    // Compter les départs réussis, pas les tentatives : un compteur qui
                    // monte pendant que rien ne part ne dit rien à personne.
                    if (ok) envois++; else echecs++;
                }
            }
            long reste = PERIODE - (System.currentTimeMillis() - t);
            try { Thread.sleep(Math.max(5, reste)); } catch (InterruptedException x) { return; }
        }
    }
}
