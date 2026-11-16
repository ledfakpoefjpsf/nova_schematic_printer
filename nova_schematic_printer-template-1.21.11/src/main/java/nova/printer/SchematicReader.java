
package nova.printer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;

public final class SchematicReader {

    private SchematicReader() {}

    public static SchematicData read(Path path) throws IOException {

        String filename =
                path.getFileName().toString().toLowerCase(Locale.ROOT);

        if (!filename.endsWith(".schem")) {
            throw new IOException(
                    "This version supports Sponge .schem files only."
            );
        }

        try (
                DataInputStream in = new DataInputStream(
                        new BufferedInputStream(
                                new GZIPInputStream(
                                        new FileInputStream(path.toFile())
                                )
                        )
                )
        ) {

            NbtTag root = readRoot(in);

            NbtTag schematic = root.child("Schematic");

            if (schematic == null) {
                schematic = root;
            }

            int version = schematic.intValue("Version", 0);

            if (version != 2) {
                throw new IOException(
                        "This reader currently supports Sponge schematic version 2."
                );
            }

            int width = schematic.number("Width");
            int height = schematic.number("Height");
            int length = schematic.number("Length");

            long volume = (long) width * height * length;

            if (width < 1 || height < 1 || length < 1
                    || volume > 2_000_000L) {
                throw new IOException(
                        "Invalid dimensions or schematic exceeds 2 million positions."
                );
            }

            NbtTag paletteTag = schematic.child("Palette");
            NbtTag blockData = schematic.child("BlockData");

            if (paletteTag == null || blockData == null
                    || blockData.bytes == null) {
                throw new IOException(
                        "Missing palette or block data."
                );
            }

            Map<Integer, BlockState> palette = new HashMap<>();

            for (Map.Entry<String, NbtTag> entry
                    : paletteTag.children.entrySet()) {

                int index = entry.getValue().intValue;

                palette.put(
                        index,
                        parseBlockState(entry.getKey())
                );
            }

            int[] indices = decodeVarInts(
                    blockData.bytes,
                    (int) volume
            );

            BlockEntry[] blocks = new BlockEntry[(int) volume];

            for (int i = 0; i < blocks.length; i++) {

                BlockState state = palette.get(indices[i]);

                if (state == null) {
                    throw new IOException(
                            "Missing palette entry: " + indices[i]
                    );
                }

                blocks[i] = new BlockEntry(state);
            }

            return new SchematicData(
                    width,
                    height,
                    length,
                    blocks
            );
        }
    }

    private static BlockState parseBlockState(String text)
            throws IOException {

        String id = text;
        String properties = null;

        int bracket = text.indexOf('[');

        if (bracket >= 0 && text.endsWith("]")) {
            id = text.substring(0, bracket);
            properties = text.substring(
                    bracket + 1,
                    text.length() - 1
            );
        }

        Identifier key = Identifier.tryParse(id);

        if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) {
            throw new IOException("Unknown block: " + id);
        }

        Block block = BuiltInRegistries.BLOCK.get(key);

        BlockState state = block.defaultBlockState();

        if (properties != null && !properties.isBlank()) {

            for (String part : properties.split(",")) {

                String[] pair = part.split("=", 2);

                if (pair.length != 2) {
                    continue;
                }

                Property<?> property =
                        block.getStateDefinition()
                                .getProperty(pair[0].trim());

                if (property != null) {
                    state = applyProperty(
                            state,
                            property,
                            pair[1].trim()
                    );
                }
            }
        }

        return state;
    }

    private static <T extends Comparable<T>>
    BlockState applyProperty(
            BlockState state,
            Property<T> property,
            String value
    ) {

        Optional<T> parsed = property.getValue(value);

        return parsed.map(
                valueObject -> state.setValue(
                        property,
                        valueObject
                )
        ).orElse(state);
    }

    private static int[] decodeVarInts(
            byte[] data,
            int expected
    ) throws IOException {

        int[] values = new int[expected];

        int cursor = 0;

        for (int i = 0; i < expected; i++) {

            int value = 0;
            int position = 0;

            while (true) {

                if (cursor >= data.length) {
                    throw new IOException(
                            "Block data ended unexpectedly."
                    );
                }

                int b = data[cursor++] & 0xFF;

                value |= (b & 0x7F) << position;

                if ((b & 0x80) == 0) {
                    break;
                }

                position += 7;

                if (position > 28) {
                    throw new IOException(
                            "Invalid VarInt."
                    );
                }
            }

            values[i] = value;
        }

        return values;
    }

    private static NbtTag readRoot(DataInputStream in)
            throws IOException {

        byte type = in.readByte();

        if (type != 10) {
            throw new IOException(
                    "NBT root must be a compound."
            );
        }

        in.readUTF();

        return readPayload(in, 10, 0);
    }

    private static NbtTag readPayload(
            DataInputStream in,
            int type,
            int depth
    ) throws IOException {

        if (depth > 64) {
            throw new IOException("NBT nesting too deep.");
        }

        NbtTag tag = new NbtTag();

        switch (type) {

            case 1 -> tag.intValue = in.readByte();
            case 2 -> tag.intValue = in.readShort();
            case 3 -> tag.intValue = in.readInt();
            case 4 -> tag.longValue = in.readLong();
            case 5 -> tag.doubleValue = in.readFloat();
            case 6 -> tag.doubleValue = in.readDouble();

            case 7 -> {
                int length = checkedLength(in.readInt());
                tag.bytes = new byte[length];
                in.readFully(tag.bytes);
            }

            case 8 -> tag.string = in.readUTF();

            case 9 -> {

                int childType = in.readUnsignedByte();
                int length = checkedLength(in.readInt());

                tag.list = new ArrayList<>(length);

                for (int i = 0; i < length; i++) {
                    tag.list.add(
                            readPayload(
                                    in,
                                    childType,
                                    depth + 1
                            )
                    );
                }
            }

            case 10 -> {

                tag.children = new LinkedHashMap<>();

                while (true) {

                    int childType = in.readUnsignedByte();

                    if (childType == 0) {
                        break;
                    }

                    String name = in.readUTF();

                    tag.children.put(
                            name,
                            readPayload(
                                    in,
                                    childType,
                                    depth + 1
                            )
                    );
                }
            }

            case 11 -> {

                int length = checkedLength(in.readInt());

                tag.ints = new int[length];

                for (int i = 0; i < length; i++) {
                    tag.ints[i] = in.readInt();
                }
            }

            case 12 -> {

                int length = checkedLength(in.readInt());

                tag.longs = new long[length];

                for (int i = 0; i < length; i++) {
                    tag.longs[i] = in.readLong();
                }
            }

            default -> throw new IOException(
                    "Unsupported NBT tag: " + type
            );
        }

        return tag;
    }

    private static int checkedLength(int length)
            throws IOException {

        if (length < 0 || length > 16_000_000) {
            throw new IOException("Invalid NBT length.");
        }

        return length;
    }

    private static final class NbtTag {

        int intValue;
        long longValue;
        double doubleValue;

        String string;
        byte[] bytes;
        int[] ints;
        long[] longs;

        Map<String, NbtTag> children =
                Collections.emptyMap();

        List<NbtTag> list =
                Collections.emptyList();

        NbtTag child(String name) {
            return children.get(name);
        }

        int number(String name) throws IOException {

            NbtTag value = child(name);

            if (value == null) {
                throw new IOException(
                        "Missing field: " + name
                );
            }

            return value.intValue;
        }

        int intValue(String name, int fallback) {

            NbtTag value = child(name);

            return value == null
                    ? fallback
                    : value.intValue;
        }
    }
}
