package com.pinktwins.elephant.data;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.logging.Logger;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.SystemUtils;
import org.json.JSONException;
import org.json.JSONObject;

import com.pinktwins.elephant.Elephant;
import com.pinktwins.elephant.eventbus.NotebookEvent;
import com.pinktwins.elephant.eventbus.VaultEvent;
import com.pinktwins.elephant.util.DropboxContentHasher;
import com.pinktwins.elephant.util.IOUtil;

public class Sync {

	private static final Logger LOG = Logger.getLogger(Sync.class.getName());

	private static enum actions {
		none, updateVaultToDropbox, updateDropboxToVault
	};

	static public String getDropboxFolder() {
		String db = "";

		if (SystemUtils.IS_OS_MAC_OSX || SystemUtils.IS_OS_LINUX) {
			String home = System.getProperty("user.home");
			db = home + File.separator + ".dropbox/info.json";
		}

		if (SystemUtils.IS_OS_WINDOWS) {
			String appData = System.getenv("APPDATA");
			String localAppData = System.getenv("LOCALAPPDATA");
			db = appData + File.separator + "Dropbox" + File.separator + "info.json";
			if (new File(db).exists()) {
				// ok
			} else {
				db = localAppData + File.separator + "Dropbox" + File.separator + "info.json";
				if (new File(db).exists()) {
					// ok
				} else {
					return "";
				}
			}
		}

		if (db.isEmpty()) {
			return "";
		}

		JSONObject info = IOUtil.loadJson(new File(db));
		if (info.has("personal")) {
			JSONObject p;
			try {
				p = info.getJSONObject("personal");
				if (p != null && p.has("path")) {
					String path = p.getString("path");
					return path;
				}
			} catch (JSONException e) {
				LOG.severe("Fail, unable to parse Dropbox info.json: " + e);
			}
		}
		return "";
	}

	public static boolean isVaultAtDropboxAppsElephant() {
		String dbPath = getDropboxFolder();
		String vaultPath = Vault.getInstance().getHome().getAbsolutePath();
		return (dbPath + File.separator + "Apps" + File.separator + "Dropbox").equals(vaultPath);
	}

	public static String getSettingsHelpText() {
		String dbPath = getDropboxFolder();
		if (dbPath.isEmpty()) {
			return "Could not locate your Dropbox folder. Is Dropbox installed?";
		}

		if (isVaultAtDropboxAppsElephant()) {
			return "Your note folder is under Dropbox. To sync only selected folders,<br/>move it out of Dropbox/Apps/Elephant:<br/><br/>1) Quit Elephant<br/>2) Move the note folder out of Dropbox<br/>3) Restart Elephant and give it the new note location.";
		}

		return "Syncing to " + dbPath + File.separator + "Apps" + File.separator
				+ "Elephant<br/>Select synced notebooks from View -> Notebooks.<br/><br/>To sync all notebooks, just use Dropbox / Apps / Elephant<br/>as your note folder and leave this off.";
	}

	public static class SyncResult {
		public int inSync, numCopied, numMoved;
		public String info;
	}

	public static SyncResult run() throws IOException {
		SyncResult r = new SyncResult();

		String dbPath = getDropboxFolder();
		String dbHome = dbPath + File.separator + "Apps" + File.separator + "Elephant";
		if (dbPath.isEmpty()) {
			r.info = "Sync: could not locate your Dropbox folder.";
			LOG.severe(r.info);
			return r;
		}

		if (isVaultAtDropboxAppsElephant()) {
			r.info = "Your note folder is under Dropbox. No syncing needed by Elephant.";
			LOG.severe(r.info);
			return r;
		}

		if (!Elephant.settings.getBoolean(Settings.Keys.SYNC)) {
			r.info = "Sync is currently disabled. Doing nothing";
			LOG.severe(r.info);
			return r;
		}

		HashSet<String> notebooks = Elephant.settings.getSyncSelection();
		if (notebooks.isEmpty()) {
			r.info = "No notebooks selected for syncing. Go to View -> Notebooks to select synced notebooks.";
			LOG.severe(r.info);
			return r;
		}

		// Read and handle files from /.events/
		// These are json files written by Elehant mobile to let us know
		// a note was moved to another folder.
		File eventsFolder = new File(dbHome + File.separator + ".events");
		if (eventsFolder.exists()) {
			File[] events = eventsFolder.listFiles(new FileFilter() {
				@Override
				public boolean accept(File f) {
					return FilenameUtils.getExtension(f.getName()).equalsIgnoreCase("json");
				}
			});
			for (File f : events) {
				JSONObject json = IOUtil.loadJson(f);
				FileUtils.deleteQuietly(f);

				String op = json.optString("op");
				if (op.equals("newnotebook")) {
					String name = json.optString("name");
					// Strip any possible paths
					name = new File(name).getName();
					File nb = new File(Vault.getInstance().getHome() + File.separator + name);
					nb.mkdirs();
					new VaultEvent(VaultEvent.Kind.notebookCreated, new Notebook(nb)).post();
					new VaultEvent(VaultEvent.Kind.notebookListChanged, new Notebook(nb)).post();
					writeNewNotebookLog(nb.getAbsolutePath());
					Elephant.settings.setSyncSelection(name, true);
					notebooks = Elephant.settings.getSyncSelection();
				}
				if (op.equals("move")) {
					String sourceNote = json.optString("sourceNote");
					String destNote = json.optString("destNote");
					String sourceMeta = json.optString("sourceMeta");
					String destMeta = json.optString("destMeta");
					String contentHash = json.optString("contentHash");
					String autorenamed = json.optString("autorenamed");

					sourceNote = sourceNote.replaceAll("/", File.separator);
					sourceMeta = sourceMeta.replaceAll("/", File.separator);
					destNote = destNote.replaceAll("/", File.separator);
					destMeta = destMeta.replaceAll("/", File.separator);

					File sourceFile = new File(Vault.getInstance().getHome() + sourceNote);

					// Verify content hash
					if (sourceFile.exists()) {
						String hash = getDropboxContentHash(sourceFile);
						if (hash.equals(contentHash)) {

							// Source confirmed to be same note,
							// Move on Vault same way as on Dropbox
							File vHome = Vault.getInstance().getHome();
							File eventSourceNote = new File(vHome + sourceNote);
							File eventSourceMeta = new File(vHome + sourceMeta);
							File sourceAttachments = new File(eventSourceNote.getAbsolutePath() + ".attachments");

							File eventDestNote = new File(vHome + destNote);
							if (!autorenamed.isEmpty()) {
								eventDestNote = new File(eventDestNote.getParentFile().getAbsolutePath() + File.separator + autorenamed);
							}

							File eventDestMeta = new File(vHome + destMeta);

							if (eventDestNote.exists() || eventDestMeta.exists()) {
								LOG.severe("Skipped handling move event, destinations already exist: " + destNote + " or " + destMeta);
								continue;
							}

							File eventSourceAttachments = new File(eventSourceNote.getAbsolutePath() + ".attachments");
							File eventDestAttachments = new File(eventDestNote.getParentFile().getAbsolutePath());

							try {
								if (eventSourceNote.exists()) {
									FileUtils.moveFile(eventSourceNote, eventDestNote);
									r.numMoved++;
									writeMoveLog(eventSourceNote.getAbsolutePath(), eventDestNote.getAbsolutePath());
								}
								if (eventSourceMeta.exists()) {
									FileUtils.moveFile(eventSourceMeta, eventDestMeta);
								}
								if (sourceAttachments.exists()) {
									FileUtils.moveDirectoryToDirectory(eventSourceAttachments, eventDestAttachments, true);
								}

								new NotebookEvent(NotebookEvent.Kind.noteMoved, eventSourceNote, eventDestNote).post();
								new VaultEvent(VaultEvent.Kind.notebookRefreshed, Note.findContainingNotebook(eventSourceNote)).post();
								new VaultEvent(VaultEvent.Kind.notebookRefreshed, Note.findContainingNotebook(eventDestNote)).post();
							} catch (IOException e) {
								LOG.severe("Note was moved on Dropbox but failed syncing the move on note folder: " + e);
							}
						}
					}
				}
			}
		}

		// Sync each notebook

		for (String notebook : notebooks) {

			// Collect all unique filenames in Vault/notebook and Dropbox/notebook

			String vaultFolder = Vault.getInstance().getHome() + File.separator + notebook;
			String dropboxFolder = dbHome + File.separator + notebook;

			File fVaultFolder = new File(vaultFolder);
			File fDropboxFolder = new File(dropboxFolder);

			if (!fVaultFolder.exists()) {
				fVaultFolder.mkdirs();
			}
			if (!fDropboxFolder.exists()) {
				fDropboxFolder.mkdirs();
			}

			File[] vaultFiles = fVaultFolder.listFiles();
			File[] dropboxFiles = fDropboxFolder.listFiles();
			File[] allFiles = ArrayUtils.addAll(vaultFiles, dropboxFiles);

			HashSet<String> uniqueNames = new HashSet<String>();
			for (File f : allFiles) {
				String name = f.getName();
				String ext = FilenameUtils.getExtension(f.getName()).toLowerCase();

				if (name.charAt(0) != '.' && !name.endsWith("~") && Notebook.isNoteExtension(ext)) {
					uniqueNames.add(f.getName());
				}
			}

			// What action is required?

			for (String noteFile : uniqueNames) {
				actions action = actions.none;

				File vaultFile = new File(fVaultFolder.getAbsolutePath() + File.separator + noteFile);
				File dropboxFile = new File(fDropboxFolder.getAbsolutePath() + File.separator + noteFile);

				if (vaultFile.exists() && !dropboxFile.exists()) {
					action = actions.updateVaultToDropbox;
				}

				if (!vaultFile.exists() && dropboxFile.exists()) {
					action = actions.updateDropboxToVault;
				}

				if (action == actions.none && vaultFile.exists() && dropboxFile.exists()) {
					long vaultModified = vaultFile.lastModified();
					long dropboxModified = dropboxFile.lastModified();

					if (vaultModified == dropboxModified) {
						action = actions.none;
					} else {
						if (vaultModified > dropboxModified) {
							action = actions.updateVaultToDropbox;
						}
						if (vaultModified < dropboxModified) {
							action = actions.updateDropboxToVault;
						}
					}
				}

				if (action != actions.none) {
					LOG.info("Sync: " + notebook + " / " + noteFile + " action " + action.toString());
				}

				// Set source and destinations
				File retainedFolder = null;
				File sourceNoteFile = null, destNoteFile = null;
				File sourceMeta = null, destMeta = null;
				File sourceAttachments = null, destAttachments = null;

				switch (action) {
				case none:
					r.inSync++;
					break;
				case updateVaultToDropbox:
					retainedFolder = new File(dbHome + File.separator + ".retained");

					sourceNoteFile = vaultFile;
					destNoteFile = dropboxFile;

					sourceMeta = metaFromFile(Vault.getInstance().getHome(), vaultFile);
					destMeta = metaFromFile(new File(dbHome), vaultFile);

					sourceAttachments = new File(vaultFile.getAbsolutePath() + ".attachments");
					destAttachments = fDropboxFolder;
					break;
				case updateDropboxToVault:
					// If destination exists, retain it
					retainedFolder = new File(Vault.getInstance().getHome() + File.separator + ".retained");

					sourceNoteFile = dropboxFile;
					destNoteFile = vaultFile;

					sourceMeta = metaFromFile(new File(dbHome), dropboxFile);
					destMeta = metaFromFile(Vault.getInstance().getHome(), vaultFile);

					sourceAttachments = new File(dropboxFile.getAbsolutePath() + ".attachments");
					destAttachments = fVaultFolder;
					break;
				default:
					break;
				}

				// Actual IO
				switch (action) {
				case none:
					break;
				case updateDropboxToVault:
				case updateVaultToDropbox:
					// If synced note target exist, move current version to .retained folder.
					// overwrite previously retained file if exists.
					if (destNoteFile.exists()) {
						File previouslyRetainedFile = new File(retainedFolder.getAbsolutePath() + File.separator + destNoteFile.getName());
						if (previouslyRetainedFile.exists()) {
							FileUtils.deleteQuietly(previouslyRetainedFile);
						}
						FileUtils.moveFileToDirectory(destNoteFile, retainedFolder, true);
					}

					// Copy note file and meta file
					FileUtils.copyFile(sourceNoteFile, destNoteFile);

					if (destMeta.exists()) {
						File previouslyRetainedMeta = new File(retainedFolder.getAbsolutePath() + File.separator + destMeta.getName());
						if (previouslyRetainedMeta.exists()) {
							FileUtils.deleteQuietly(previouslyRetainedMeta);
						}
						FileUtils.moveFileToDirectory(destMeta, retainedFolder, true);
					}

					FileUtils.copyFile(sourceMeta, destMeta, true);

					// Copy all attachments over. If destination attachments exist,
					// folders are combined, priority given to source.
					if (sourceAttachments.exists()) {
						FileUtils.copyDirectoryToDirectory(sourceAttachments, destAttachments);
					}
					r.numCopied++;
					writeCopyLog(sourceNoteFile.getAbsolutePath(), destNoteFile.getAbsolutePath());

					if (action == actions.updateDropboxToVault) {
						new VaultEvent(VaultEvent.Kind.notebookRefreshed, Note.findContainingNotebook(destNoteFile)).post();
					}
					break;
				default:
					break;
				}
			}
		}

		return r;
	}

	private static File metaFromFile(File home, File note) {
		File notebook = note.getParentFile();
		String metaPath = home.getAbsolutePath() + File.separator + ".meta" + File.separator + notebook.getName() + "_" + note.getName();
		File m = new File(metaPath);
		return m;
	}

	private static String dbHome() {
		String dbPath = getDropboxFolder();
		String dbHome = dbPath + File.separator + "Apps" + File.separator + "Elephant";
		return dbHome;
	}

	private static boolean noSync() {
		String dbPath = getDropboxFolder();
		return dbPath.isEmpty() || isVaultAtDropboxAppsElephant() || !Elephant.settings.getBoolean(Settings.Keys.SYNC);
	}

	// When syncing is enabled and note is autorenamed on Vault (to avoid having files of same name),
	// perform the renaming on Dropbox immediately as well.
	public static void onNoteRename(File noteFile, File metaFile, File newFilename) {
		if (noSync()) {
			return;
		}

		String notebookName = noteFile.getParentFile().getName();
		if (!Elephant.settings.getSyncSelection().contains(notebookName)) {
			return;
		}

		File sourceNote = new File(dbHome() + File.separator + noteFile.getParentFile().getName() + File.separator + noteFile.getName());
		File sourceMeta = new File(dbHome() + File.separator + ".meta" + File.separator + noteFile.getParentFile().getName() + "_" + noteFile.getName());
		File sourceAttachments = new File(sourceNote.getAbsolutePath() + ".attachments");

		File destNote = new File(sourceNote.getParentFile().getAbsolutePath() + File.separator + newFilename.getName());
		File destMeta = new File(
				sourceMeta.getParentFile().getAbsolutePath() + File.separator + newFilename.getParentFile().getName() + "_" + newFilename.getName());
		File destAttachments = new File(destNote.getAbsolutePath() + ".attachments");

		if (destNote.exists() || destMeta.exists() || destAttachments.exists()) {
			LOG.severe("Failed syncing note renaming on Dropbox, target exists:\nNote file: " + destNote.getAbsolutePath() + "\nor meta file: "
					+ destMeta.getAbsolutePath() + "\nor attachments: " + destAttachments.getAbsolutePath());
			return;
		}

		try {
			if (sourceNote.exists()) {
				FileUtils.moveFile(sourceNote, destNote);
				writeMoveLog(sourceNote.getAbsolutePath(), destNote.getAbsolutePath());
			}
			if (sourceMeta.exists()) {
				FileUtils.moveFile(sourceMeta, destMeta);
			}
			if (sourceAttachments.exists()) {
				FileUtils.moveFile(sourceAttachments, destAttachments);
			}
		} catch (IOException e) {
			LOG.severe("Note was renamed but failed syncing the rename on Dropbox: " + noteFile.getAbsolutePath() + " -> " + newFilename.getName());
		}
	}

	// When note is moved to another notebook, move note under Dropbox immediately as well.
	public static void onNoteMove(File noteFile, File metaFile, File destNotebook) {
		if (noSync()) {
			return;
		}

		HashSet<String> syncedNotebooks = Elephant.settings.getSyncSelection();
		boolean isSourceSynced = syncedNotebooks.contains(noteFile.getParentFile().getName());
		boolean isDestSynced = syncedNotebooks.contains(destNotebook.getName());
		isDestSynced = isDestSynced || destNotebook.getName().equalsIgnoreCase("Trash");

		if (!isSourceSynced && !isDestSynced) {
			// Neither notebook is synced, do nothing here
			return;
		}

		if (isSourceSynced && !isDestSynced) {
			// Source notebook is synced but dest isn't - remove note: Dropbox / source
			File dbxNote = new File(dbHome() + File.separator + noteFile.getParentFile().getName() + File.separator + noteFile.getName());
			File dbxMeta = new File(dbHome() + File.separator + ".meta" + File.separator + noteFile.getParentFile().getName() + "_" + noteFile.getName());
			File dbxAttachments = new File(dbxNote.getAbsolutePath() + ".attachments");

			if (dbxNote.exists()) {
				FileUtils.deleteQuietly(dbxNote);
				writeDeleteLog(dbxNote.getAbsolutePath());
			}
			if (dbxMeta.exists()) {
				FileUtils.deleteQuietly(dbxMeta);
			}
			if (dbxAttachments.exists()) {
				FileUtils.deleteQuietly(dbxAttachments);
			}

			return;
		}

		if (!isSourceSynced && isDestSynced) {
			// Source notebook isn't synced but dest is:
			// Next sync() will copy the note over, do nothing here
			return;
		}

		// Both notebooks are synced:

		File sourceNote = new File(dbHome() + File.separator + noteFile.getParentFile().getName() + File.separator + noteFile.getName());
		File sourceMeta = new File(dbHome() + File.separator + ".meta" + File.separator + noteFile.getParentFile().getName() + "_" + noteFile.getName());
		File sourceAttachments = new File(sourceNote.getAbsolutePath() + ".attachments");

		File destNote = new File(dbHome() + File.separator + destNotebook.getName() + File.separator + noteFile.getName());
		File destMeta = new File(dbHome() + File.separator + ".meta" + File.separator + destNotebook.getName() + "_" + noteFile.getName());
		File destDropboxNotebook = new File(dbHome() + File.separator + destNotebook.getName());

		if (destNote.exists() || destMeta.exists()) {
			LOG.severe("Note was moved but failed syncing the move.\nTried moving note under Dropbox: " + sourceNote.getAbsolutePath() + "\nto notebook: "
					+ destDropboxNotebook + "\nbut file already exists: " + destNote.getAbsolutePath() + "\nor: " + destMeta.getAbsolutePath());
			return;
		}

		try {
			if (sourceNote.exists()) {
				FileUtils.moveFile(sourceNote, destNote);
				writeMoveLog(sourceNote.getAbsolutePath(), destNote.getAbsolutePath());
			}
			if (sourceMeta.exists()) {
				FileUtils.moveFile(sourceMeta, destMeta);
			}
			if (sourceAttachments.exists()) {
				FileUtils.moveDirectoryToDirectory(sourceAttachments, destDropboxNotebook, true);
			}
		} catch (IOException e) {
			LOG.severe("Note was moved but failed syncing the move on Dropbox: " + e);
		}
	}

	static final char[] HEX_DIGITS = new char[] { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

	public static String hex(byte[] data) {
		char[] buf = new char[2 * data.length];
		int i = 0;
		for (byte b : data) {
			buf[i++] = HEX_DIGITS[(b & 0xf0) >>> 4];
			buf[i++] = HEX_DIGITS[b & 0x0f];
		}
		return new String(buf);
	}

	public static String getDropboxContentHash(File f) throws IOException {
		MessageDigest hasher = new DropboxContentHasher();
		byte[] buf = new byte[1024];
		InputStream in = new FileInputStream(f);
		try {
			while (true) {
				int n = in.read(buf);
				if (n < 0)
					break; // EOF
				hasher.update(buf, 0, n);
			}
		} finally {
			in.close();
		}

		return hex(hasher.digest());
	}

	public static void writeNewNotebookLog(String name) {
		writeLog(System.currentTimeMillis() + ",NEW," + name + "\n");
	}

	public static void writeDeleteLog(String note) {
		writeLog(System.currentTimeMillis() + ",DEL," + note + "\n");
	}

	public static void writeCopyLog(String note, String destination) {
		writeLog(System.currentTimeMillis() + ",COPY," + note + "," + destination + "\n");
	}

	public static void writeMoveLog(String note, String destination) {
		writeLog(System.currentTimeMillis() + ",MOVE," + note + "," + destination + "\n");
	}

	public static void writeLog(String str) {
		File f = new File(Vault.getInstance().getHome() + File.separator + ".synclog");
		FileWriter fr;
		try {
			fr = new FileWriter(f, true);
			BufferedWriter br = new BufferedWriter(fr);
			br.write(str);
			br.close();
			fr.close();
		} catch (IOException e) {
			LOG.severe("Failed writing sync log: " + e);
		}
	}
}
