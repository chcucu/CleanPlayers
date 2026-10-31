package dev.cleanplayers.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.stream.Stream;

public final class FileUtil {

    private FileUtil() {
    }

    public static void ensureDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            Files.createDirectories(directory);
        }
    }

    public static void atomicWriteString(Path target, String content) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        ensureDirectory(parent);
        Path temp = parent.resolve(target.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        moveAtomic(temp, target);
    }

    public static void moveAtomic(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Copies a file and verifies the copy has the exact same byte size. Throws when the copy
     * cannot be verified, which is the signal callers use to skip a deletion.
     */
    public static long copyVerified(Path source, Path target) throws IOException {
        long size = Files.size(source);
        ensureDirectory(target.toAbsolutePath().getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        long copied = Files.size(target);
        if (copied != size) {
            throw new IOException("Backup verification failed for " + source.getFileName()
                    + " (source " + size + " bytes, copy " + copied + " bytes)");
        }
        return copied;
    }

    public static void deleteIfExists(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    public static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static long directorySize(Path root) {
        if (!Files.exists(root)) {
            return 0L;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException ex) {
                    return 0L;
                }
            }).sum();
        } catch (IOException ex) {
            return 0L;
        }
    }

    public static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            return 0L;
        }
    }

    public static boolean isWithin(Path child, Path parent) {
        Path normalizedChild = child.toAbsolutePath().normalize();
        Path normalizedParent = parent.toAbsolutePath().normalize();
        return normalizedChild.startsWith(normalizedParent) && !normalizedChild.equals(normalizedParent);
    }

    /** World folder names must never be able to escape the server root. */
    public static boolean isSafeWorldName(String name) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")) {
            return false;
        }
        return name.matches("[A-Za-z0-9_.-]+") && !name.contains("..");
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024.0) {
            return String.format("%.1f MB", mb);
        }
        return String.format("%.1f GB", mb / 1024.0);
    }

    public static void requireWritable(Path directory) throws IOException {
        ensureDirectory(directory);
        if (!Files.isWritable(directory)) {
            throw new IOException("Directory is not writable: " + directory);
        }
    }

    public static Path child(Path parent, String name) {
        try {
            return parent.resolve(name);
        } catch (RuntimeException ex) {
            throw new UncheckedIOException(new IOException("Invalid path component: " + name, ex));
        }
    }
}
