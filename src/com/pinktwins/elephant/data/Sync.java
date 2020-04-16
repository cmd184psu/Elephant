package com.pinktwins.elephant.data;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.logging.Logger;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.SystemUtils;
import org.json.JSONException;
import org.json.JSONObject;

import com.pinktwins.elephant.Elephant;
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

		return "Syncing to " + dbPath + File.separator + "Apps" + File.separator + "Elephant<br/>Select synced notebooks from View -> Notebooks.";
	}

	public static class SyncResult {
		public int inSync, numCopied;
	}

	public static SyncResult run() throws IOException {
		String dbPath = getDropboxFolder();
		String dbHome = dbPath + File.separator + "Apps" + File.separator + "Elephant";
		if (dbPath.isEmpty()) {
			LOG.severe("Sync: could not locate your Dropbox folder.");
			return null;
		}

		if (isVaultAtDropboxAppsElephant()) {
			LOG.info("Your note folder is under Dropbox. No syncing needed.");
			return null;
		}

		if (!Elephant.settings.getBoolean(Settings.Keys.SYNC)) {
			LOG.info("Sync is currently disabled. Doing nothing");
			return null;
		}

		HashSet<String> notebooks = Elephant.settings.getSyncSelection();
		if (notebooks.isEmpty()) {
			LOG.info("No notebooks selected for syncing.");
			return null;
		}

		// Sync each notebook

		int inSync = 0, numCopied = 0;
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
					System.out.println("Note " + noteFile + " action " + action.toString());
				}

				// Set source and destinations
				File retainedFolder = null;
				File sourceNoteFile = null, destNoteFile = null;
				File sourceMeta = null, destMeta = null;
				File sourceAttachments = null, destAttachments = null;

				switch (action) {
				case none:
					inSync++;
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
					numCopied++;
					break;
				default:
					break;
				}
			}
		}

		SyncResult r = new SyncResult();
		r.inSync = inSync;
		r.numCopied = numCopied;
		return r;
	}

	private static File metaFromFile(File home, File note) {
		File notebook = note.getParentFile();
		String metaPath = home.getAbsolutePath() + File.separator + ".meta" + File.separator + notebook.getName() + "_" + note.getName();
		File m = new File(metaPath);
		return m;
	}

}
