package fr.regie.patch;

import android.content.ContentResolver;
import android.net.Uri;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Lecture d'un fichier GDTF (archive zip contenant description.xml).
 *
 * L'empreinte d'un mode est le plus grand décalage déclaré par ses canaux,
 * pixels compris : c'est le nombre de canaux à réserver.
 *
 * Un appareil à pixels (Spiider en mode pixel, barre LED…) ne décrit les
 * canaux d'un pixel qu'une fois, sur une géométrie modèle ; chaque référence
 * à ce modèle (GeometryReference) en pose une copie, décalée par son Break.
 * Sans ce dépliage, un Spiider en « Mode 8 - Pixel RGBW » comptait 66 canaux
 * au lieu de 110.
 */
public class Gdtf {

    /** Une référence à une géométrie modèle : où elle est posée, ce qu'elle copie, ses Break. */
    private static final class Ref {
        String dans, modele;
        final List<int[]> coupures = new ArrayList<int[]>();   // { DMXBreak, DMXOffset }
    }

    private static final class Canal {
        int[] decal;                    // octet fort d'abord
        String coupure, geo, attr, defCanal, hl, fnInit, defInit, defNom, defPremier;
        String ouvert;                  // « Open » d'un shutter, quand le fichier le nomme
    }

    private static final class Mode {
        String nom, geo;
        final List<Canal> canaux = new ArrayList<Canal>();
    }

    public static String lire(ContentResolver cr, Uri uri) {
        InputStream in = null;
        try {
            in = cr.openInputStream(uri);
            if (in == null) return erreur("fichier illisible");
            ZipInputStream z = new ZipInputStream(in);
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String nom = e.getName();
                if (nom != null && nom.equalsIgnoreCase("description.xml")) return analyser(z);
            }
            return erreur("description.xml introuvable — est-ce bien un fichier GDTF ?");
        } catch (Exception ex) {
            return erreur("lecture impossible");
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignore) { }
        }
    }

    private static String analyser(InputStream in) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(in, null);

        String nom = "", fabricant = "";
        // Les géométries : la géométrie de premier niveau de chacune, et les références.
        Map<String, String> sommet = new HashMap<String, String>();
        List<Ref> refs = new ArrayList<Ref>();
        List<String> pile = null;       // balises ouvertes dans <Geometries>, null ailleurs
        Ref ref = null;
        List<Mode> modes = new ArrayList<Mode>();
        Mode mode = null;
        Canal canal = null;
        String logique = null;          // attribut du canal logique en cours
        String fnAttr = "";             // attribut de la fonction en cours
        int nFn = 0;

        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String t = p.getName();
                if (pile != null) {
                    // Toute balise nommée y est une géométrie, sauf ce qui la détaille.
                    String n = attr(p, "Name");
                    boolean geo = n != null && !"Break".equals(t) && !"Protocol".equals(t) && !"PinPatch".equals(t);
                    String haut = null;
                    for (String x : pile) if (x != null) { haut = x; break; }
                    if (geo) {
                        sommet.put(n, haut != null ? haut : n);
                        if ("GeometryReference".equals(t)) {
                            ref = new Ref();
                            ref.dans = haut != null ? haut : n;
                            ref.modele = attr(p, "Geometry");
                            refs.add(ref);
                        }
                    } else if ("Break".equals(t) && ref != null) {
                        ref.coupures.add(new int[] { entier(attr(p, "DMXBreak"), 1), entier(attr(p, "DMXOffset"), 1) });
                    }
                    pile.add(geo ? n : null);
                } else if ("Geometries".equals(t)) {
                    pile = new ArrayList<String>();
                } else if ("FixtureType".equals(t)) {
                    if (nom.isEmpty()) {
                        nom = val(attr(p, "LongName"), attr(p, "Name"));
                        fabricant = val(attr(p, "Manufacturer"), "");
                    }
                } else if ("DMXMode".equals(t)) {
                    mode = new Mode();
                    mode.nom = attr(p, "Name");
                    mode.geo = attr(p, "Geometry");
                    boolean deja = false;
                    for (Mode m : modes) if (m.nom.equals(mode.nom)) deja = true;
                    if (mode.nom == null || deja) mode = null;
                    else modes.add(mode);
                } else if ("DMXChannel".equals(t) && mode != null) {
                    canal = new Canal();
                    canal.decal = decalages(attr(p, "Offset"));
                    canal.coupure = attr(p, "DMXBreak");
                    canal.geo = attr(p, "Geometry");
                    canal.defCanal = attr(p, "Default");        // GDTF 1.0
                    canal.hl = attr(p, "Highlight");
                    canal.fnInit = attr(p, "InitialFunction");
                } else if ("LogicalChannel".equals(t) && canal != null) {
                    logique = val(attr(p, "Attribute"), "");
                    if (canal.attr == null) canal.attr = logique;
                    nFn = 0;
                } else if ("ChannelFunction".equals(t) && canal != null) {
                    nFn++;
                    fnAttr = val(attr(p, "Attribute"), "");
                    String d = val(attr(p, "Default"), val(attr(p, "DMXFrom"), "0/1"));
                    // Sans nom, une fonction s'appelle « attribut numéro » (« Dimmer 1 »).
                    String n = val(attr(p, "Name"), val(attr(p, "Attribute"), "") + " " + nFn);
                    if (canal.defPremier == null) canal.defPremier = d;
                    if (canal.ouvert == null && ouvre(fnAttr, n)) canal.ouvert = val(attr(p, "DMXFrom"), "0/1");
                    // InitialFunction vise « canal.canal logique.fonction ».
                    String ini = canal.fnInit;
                    if (ini != null) {
                        if (canal.defInit == null && ini.endsWith("." + logique + "." + n)) canal.defInit = d;
                        if (canal.defNom == null && ini.substring(ini.lastIndexOf('.') + 1).equals(n)) canal.defNom = d;
                    }
                } else if ("ChannelSet".equals(t) && canal != null) {
                    // Shutter « Closed » 0, « Open » 32 : le nom dit où il s'ouvre.
                    if (canal.ouvert == null && ouvre(fnAttr, attr(p, "Name")) && attr(p, "DMXFrom") != null)
                        canal.ouvert = attr(p, "DMXFrom");
                }
            } else if (ev == XmlPullParser.END_TAG) {
                String t = p.getName();
                if (pile != null) {
                    if ("Geometries".equals(t)) pile = null;
                    else {
                        if (!pile.isEmpty()) pile.remove(pile.size() - 1);
                        if ("GeometryReference".equals(t)) ref = null;
                    }
                } else if ("DMXChannel".equals(t)) {
                    if (canal != null && mode != null) mode.canaux.add(canal);
                    canal = null;
                } else if ("DMXMode".equals(t)) {
                    mode = null;
                }
            }
            ev = p.next();
        }

        /* Pour allumer un appareil : ses canaux d'intensité (dim), ses couleurs
           additives à fond (plein : R, G, B, W… à fond, il sort blanc) et la
           valeur des autres canaux (def : pan au centre, shutter ouvert…).
           La valeur Highlight du fichier, quand il en donne une, passe avant :
           c'est celle que le fabricant a choisie pour la mise en évidence. */
        JSONArray a = new JSONArray();
        for (Mode m : modes) {
            int ch = 0;
            JSONArray dim = new JSONArray(), plein = new JSONArray();
            Map<Integer, Integer> def = new TreeMap<Integer, Integer>();
            for (Canal c : m.canaux) {
                if (c.decal == null || c.decal.length == 0) continue;
                List<int[]> places = places(c, m.geo, sommet, refs);
                int n = c.decal.length;
                long max = n >= 8 ? -1L : (1L << (8 * n)) - 1;
                Long hl = valeur(c.hl, n);
                boolean intensite = "Dimmer".equals(c.attr);
                boolean couleur = c.attr != null && (c.attr.startsWith("ColorAdd_") || c.attr.startsWith("ColorRGB_"));
                long v;
                if (hl != null) v = hl;
                else if (intensite || couleur) v = max;
                else {
                    // Un shutter se met ouvert : certains fichiers le laissent fermé au repos.
                    boolean shutter = c.attr != null && c.attr.matches("Shutter\\d*");
                    Long d = valeur(shutter && c.ouvert != null ? c.ouvert
                            : c.defCanal != null ? c.defCanal : c.defInit != null ? c.defInit
                            : c.defNom != null ? c.defNom : c.defPremier, n);
                    v = d != null ? d : 0;
                }
                for (int[] o : places) {
                    for (int x : o) if (x > ch) ch = x;
                    if (intensite) for (int x : o) dim.put(x);
                    else if (couleur && v == max) for (int x : o) plein.put(x);
                    if ((intensite || couleur) && v == max) continue;
                    for (int i = 0; i < n; i++) {
                        int b = (int) ((v >> (8 * (n - 1 - i))) & 255);
                        if (b > 0) def.put(o[i], b);
                    }
                }
            }
            if (ch <= 0) continue;
            JSONObject j = new JSONObject();
            j.put("nom", m.nom);
            j.put("ch", ch);
            // Un PAR LED en RGB n'a pas de Dimmer : ses couleurs suffisent à l'allumer.
            if (dim.length() > 0 || plein.length() > 0) {
                j.put("dim", dim);
                j.put("plein", plein);
                JSONObject d = new JSONObject();
                for (Map.Entry<Integer, Integer> x : def.entrySet())
                    d.put(String.valueOf(x.getKey()), x.getValue());
                j.put("def", d);
            }
            a.put(j);
        }
        JSONObject o = new JSONObject();
        o.put("pret", true);
        o.put("nom", (fabricant.isEmpty() ? "" : fabricant + " ") + nom);
        o.put("modes", a);
        return o.toString();
    }

    /**
     * Où tombe un canal : à son décalage, ou, s'il est sur une géométrie
     * modèle, une fois par référence à ce modèle, décalé du Break de la
     * référence (« Overwrite » prend le dernier Break). Seule la première
     * liaison DMX (DMXBreak 1) compte : c'est elle qu'on patche.
     */
    private static List<int[]> places(Canal c, String racine, Map<String, String> sommet, List<Ref> refs) {
        List<int[]> l = new ArrayList<int[]>();
        boolean ecrase = c.coupure != null && c.coupure.trim().equalsIgnoreCase("Overwrite");
        int coupure = ecrase ? 1 : entier(c.coupure, 1);
        String haut = c.geo == null ? null : sommet.get(c.geo);
        if (racine != null && haut != null && !haut.equals(racine)) {
            boolean modele = false;
            for (Ref r : refs) {
                if (!haut.equals(r.modele) || !racine.equals(r.dans)) continue;
                modele = true;
                int[] b = r.coupures.isEmpty() ? new int[] { 1, 1 } : null;
                if (b == null && ecrase) b = r.coupures.get(r.coupures.size() - 1);
                else if (b == null) for (int[] x : r.coupures) if (x[0] == coupure) { b = x; break; }
                if (b == null || b[0] != 1) continue;
                int[] o = new int[c.decal.length];
                for (int i = 0; i < o.length; i++) o[i] = b[1] - 1 + c.decal[i];
                l.add(o);
            }
            if (modele) return l;
        }
        if (coupure == 1) l.add(c.decal);
        return l;
    }

    private static int[] decalages(String offset) {
        if (offset == null || offset.trim().isEmpty()) return null;
        String[] t = offset.split(",");
        int[] r = new int[t.length];
        try { for (int i = 0; i < t.length; i++) r[i] = Integer.parseInt(t[i].trim()); }
        catch (NumberFormatException e) { return null; }
        return r;
    }

    /**
     * Une valeur GDTF ramenée à la résolution du canal : « 255/1 » sur un canal
     * 16 bits vaut 65535 (les octets se répètent), « 255/1s » 65280 (ils se
     * décalent), « 32768/2 » sur 8 bits vaut 128. null pour « None ».
     */
    private static Long valeur(String dmx, int octets) {
        if (dmx == null) return null;
        String s = dmx.trim();
        if (s.isEmpty() || s.equalsIgnoreCase("None")) return null;
        try {
            boolean decale = s.endsWith("s") || s.endsWith("S");
            if (decale) s = s.substring(0, s.length() - 1);
            String[] t = s.split("/");
            long v = Long.parseLong(t[0].trim());
            int n = t.length > 1 ? Integer.parseInt(t[1].trim()) : 1;
            if (n < 1 || n > 7 || octets < 1 || octets > 7) return null;
            if (n >= octets) return v >> (8 * (n - octets));
            if (decale) return v << (8 * (octets - n));
            long r = 0;
            for (int i = 0; i < octets; i++) r = (r << 8) | ((v >> (8 * (n - 1 - i % n))) & 255);
            return r;
        } catch (Exception e) { return null; }
    }

    private static int entier(String s, int defaut) {
        try { return s == null ? defaut : Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return defaut; }
    }

    private static String attr(XmlPullParser p, String nom) {
        return p.getAttributeValue(null, nom);
    }

    /** Une fonction ou un réglage « Open » d'un attribut Shutter1, Shutter2… */
    private static boolean ouvre(String attribut, String nom) {
        if (attribut == null || !attribut.matches("Shutter\\d*") || nom == null) return false;
        String n = nom.trim().toLowerCase(java.util.Locale.ROOT);
        return n.equals("open") || n.startsWith("open ") || n.equals("shutter open");
    }

    private static String val(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        return b == null ? "" : b.trim();
    }

    private static String erreur(String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("erreur", message);
            return o.toString();
        } catch (Exception e) { return "{\"erreur\":\"inconnue\"}"; }
    }
}
