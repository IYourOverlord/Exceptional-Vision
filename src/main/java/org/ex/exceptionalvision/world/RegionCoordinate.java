package org.ex.exceptionalvision.world;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Region coordinates in region-space (each unit = 32x32 chunks = 512x512 blocks).
 * Matches the {@code r.X.Z.mca} naming convention of the Anvil region format.
 */
public record RegionCoordinate(int x, int z) {

    private static final Pattern FILE_NAME_PATTERN =
            Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");

    public static Optional<RegionCoordinate> fromFileName(String fileName) {
        Matcher matcher = FILE_NAME_PATTERN.matcher(fileName);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int x = Integer.parseInt(matcher.group(1));
        int z = Integer.parseInt(matcher.group(2));
        return Optional.of(new RegionCoordinate(x, z));
    }

    public String fileName() {
        return "r." + x + "." + z + ".mca";
    }

    /** World-space chunk X of the region's minimum corner. */
    public int minChunkX() {
        return x << 5;
    }

    /** World-space chunk Z of the region's minimum corner. */
    public int minChunkZ() {
        return z << 5;
    }
}
