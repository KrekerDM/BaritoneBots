package io.github.krekerdm.baritonebots.common.schematic;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads Sponge (.schem v1/v2/v3) and Litematica (.litematic) files into a {@link Schematic}. The format is
 * detected from the NBT content, not the file name, so a Sponge file named {@code .schematic} works too.
 * Legacy MCEdit {@code .schematic} files (numeric block ids) are rejected as {@code unsupported}.
 */
public final class SchematicLoader {
    /** File extensions the panel accepts for upload. */
    public static final List<String> EXTENSIONS = List.of(".schem", ".schematic", ".litematic");

    private SchematicLoader() {
    }

    /**
     * @throws SchematicException with a {@link SchematicException#reason()} code for unsupported or corrupt data
     * @throws IOException        when the file cannot be read or is not valid NBT
     */
    public static Schematic load(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return load(in);
        }
    }

    /** Same as {@link #load(Path)} for a stream (not closed). */
    public static Schematic load(InputStream in) throws IOException {
        return fromNbt(NbtReader.read(in));
    }

    /** Builds a schematic from an already parsed NBT root compound. */
    public static Schematic fromNbt(Map<String, Object> root) throws SchematicException {
        if (LitematicaReader.accepts(root)) {
            return LitematicaReader.read(root);
        }
        if (SpongeReader.accepts(root)) {
            return SpongeReader.read(root);
        }
        if (root.get("Blocks") instanceof byte[] && root.containsKey("Width")) {
            throw new SchematicException(SchematicException.UNSUPPORTED,
                    "legacy MCEdit schematic with numeric block ids; re-save it as .schem (WorldEdit) or .litematic");
        }
        if (root.containsKey("blocks") && root.containsKey("palette") && root.containsKey("size")) {
            throw new SchematicException(SchematicException.UNSUPPORTED,
                    "vanilla structure (.nbt) files are not supported; re-save it as .schem or .litematic");
        }
        throw new SchematicException(SchematicException.UNSUPPORTED, "unknown schematic format");
    }

    /** True when the file name has one of {@link #EXTENSIONS} (case-insensitive). */
    public static boolean hasSupportedExtension(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }
}
