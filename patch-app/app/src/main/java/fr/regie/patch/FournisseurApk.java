package fr.regie.patch;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Sert l'APK téléchargé à l'installateur d'Android.
 *
 * Depuis Android 7, passer un `file://` à une autre application lève une
 * exception : il faut une adresse `content://`, dont le droit de lecture voyage
 * avec l'intention. Le projet n'a aucune dépendance — pas d'AndroidX, donc pas
 * de `FileProvider` — et ces quelques lignes font le même travail pour le seul
 * fichier qui en a besoin. Rien d'autre n'est servi : un nom de fichier qui
 * n'est pas celui de l'APK ne donne accès à rien.
 */
public class FournisseurApk extends ContentProvider {

    public static final String AUTORITE = "fr.regie.patch.fichiers";

    @Override
    public boolean onCreate() { return true; }

    private File fichier(Uri uri) {
        File f = Maj.fichier(getContext());
        if (f == null) return null;
        return f.getName().equals(uri.getLastPathSegment()) ? f : null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fichier(uri);
        if (f == null || !f.exists()) throw new FileNotFoundException(String.valueOf(uri));
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** L'installateur demande le nom affiché et la taille avant d'ouvrir. */
    @Override
    public Cursor query(Uri uri, String[] colonnes, String selection,
                        String[] arguments, String tri) {
        File f = fichier(uri);
        if (f == null || !f.exists()) return null;
        String[] c = colonnes != null ? colonnes
                : new String[]{ OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        Object[] ligne = new Object[c.length];
        for (int i = 0; i < c.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(c[i])) ligne[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(c[i])) ligne[i] = f.length();
        }
        MatrixCursor curseur = new MatrixCursor(c, 1);
        curseur.addRow(ligne);
        return curseur;
    }

    @Override
    public String getType(Uri uri) { return "application/vnd.android.package-archive"; }

    @Override
    public Uri insert(Uri uri, ContentValues valeurs) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] arguments) { return 0; }

    @Override
    public int update(Uri uri, ContentValues valeurs, String selection, String[] arguments) { return 0; }
}
