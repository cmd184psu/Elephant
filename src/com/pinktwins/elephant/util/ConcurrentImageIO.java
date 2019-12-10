package com.pinktwins.elephant.util;

import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.IOException;
import java.util.Map;

import javax.imageio.ImageIO;

public class ConcurrentImageIO {

	private static Map<String, Object> locks = Factory.newHashMap();

	public static boolean write(RenderedImage im, String formatName, File output) throws IOException {
		String key = output.getAbsolutePath();
		Object lock = new Object();
		synchronized (lock) {
			locks.put(key, lock);
			try {
				boolean res = ImageIO.write(im, formatName, output);
				locks.remove(key);
				return res;
			} catch (Exception e) {
				locks.remove(key);
				throw e;
			}
		}
	}

	public static BufferedImage read(File input) throws IOException {
		String key = input.getAbsolutePath();
		Object lock = locks.get(key);
		if (lock == null) {
			return ImageIO.read(input);
		} else {
			synchronized (lock) {
				return ImageIO.read(input);
			}
		}
	}
}
