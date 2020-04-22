package com.pinktwins.elephant;

import java.awt.Image;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import javax.swing.JTextPane;

import org.apache.commons.lang3.StringUtils;

import com.pinktwins.elephant.data.Note;
import com.pinktwins.elephant.ui.RetinaImageIcon;
import com.pinktwins.elephant.util.ConcurrentImageIO;
import com.pinktwins.elephant.util.Factory;
import com.pinktwins.elephant.util.Images;

public class NoteAttachments {

	private static final Logger LOG = Logger.getLogger(NoteAttachments.class.getName());

	private static final String ATTACHMENTSTRING_DIRTY = "*d";

	private Map<Object, File> attachments = Factory.newHashMap();
	private String loadMark;

	private int noteHash;

	public Set<Object> keySet() {
		return new HashSet<Object>(attachments.keySet());
	}

	public void put(Object o, File f) {
		attachments.put(o, f);
	}

	public File get(Object o) {
		return attachments.get(o);
	}

	public void remove(Object o) {
		attachments.remove(o);
	}

	void insertFileIntoNote(NoteEditor editor, Note note, File f, int position) {
		if (editor.getWidth() <= 0) {
			throw new AssertionError();
		}

		JTextPane notePane = editor.editor.getTextPane();
		int caret = notePane.getCaretPosition();

		if (Images.isImage(f)) {
			try {
				Image i = null;

				i = editor.imageAttachmentImageScaler.getCachedScale(f);

				if (i == null) {
					i = ConcurrentImageIO.read(f);
					if (i != null) {
						i = editor.imageAttachmentImageScaler.scale(i, f);
					}
				}

				if (i != null) {
					// abort if editor has changed note
					if (noteHash != note.hashCode()) {
						return;
					}

					RetinaImageIcon ii = new RetinaImageIcon(i);

					if (position > notePane.getDocument().getLength()) {
						position = 0;
					}

					try {
						notePane.setCaretPosition(position);
					} catch (IllegalArgumentException e) {
						LOG.severe("Fail: " + e);
					}

					notePane.insertIcon(ii);

					attachments.put(ii, f);
				}
			} catch (IOException e) {
				LOG.severe("Fail: " + e);
			}
		} else {
			FileAttachment aa = new FileAttachment(f, note, editor.editorWidthScaler, editor.editorController);

			int len = notePane.getText().length();
			if (position < 0) {
				position = 0;
			}
			if (position > len) {
				position = len;
			}

			try {
				notePane.setCaretPosition(position);
			} catch (IllegalArgumentException e) {
				LOG.info("Note " + note.file().getAbsolutePath() + " attachment " + f.getName() + " position in meta is invalid.");
			}

			notePane.insertComponent(aa);

			attachments.put(aa, f);
		}

		notePane.setCaretPosition(caret);
	}

	private String getAttachmentString() {
		List<String> files = new ArrayList<String>();

		for (File f : attachments.values()) {
			files.add(f.getAbsolutePath());
		}

		Collections.sort(files);
		return StringUtils.join(files, ":");
	}

	public void loaded() {
		loadMark = getAttachmentString();
	}

	public void makeDirty() {
		loadMark = ATTACHMENTSTRING_DIRTY;
	}

	public boolean didChange() {
		if (loadMark == null) {
			return false;
		}

		return !loadMark.equals(getAttachmentString());
	}

	public void areForNote(Note note) {
		noteHash = note.hashCode();
	}

	public File findFile(String filename) {
		for (File f : attachments.values()) {
			try {
				if (URLDecoder.decode(f.getName(), "UTF-8").equals(URLDecoder.decode(filename, "UTF-8"))) {
					return f;
				}
			} catch (UnsupportedEncodingException e) {
				LOG.severe("Fail: URLDecoding with UTF-8: " + filename);
				return null;
			}
		}
		return null;
	}
}
