
package nova.printer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;

public final class PrinterScreen extends Screen {

    private String fileLabel = "No schematic loaded";
    private String status = "Select a schematic to begin.";

    private SchematicData loaded;

    private int offsetX;
    private int offsetY;
    private int offsetZ;

    private int rotation;
    private boolean mirrorX;
    private boolean replaceAir;

    public PrinterScreen() {
        super(Component.literal("Nova Schematic Printer"));
    }

    @Override
    protected void init() {

        int center = width / 2;
        int y = height / 2 - 85;

        addRenderableWidget(
                Button.builder(
                        Component.literal("LOAD SCHEMATIC"),
                        button -> loadFile()
                ).bounds(center - 100, y, 200, 22).build()
        );

        y += 29;

        addRenderableWidget(
                Button.builder(
                        Component.literal("ROTATE 90°"),
                        button -> {
                            rotation = (rotation + 1) % 4;
                        }
                ).bounds(center - 100, y, 97, 20).build()
        );

        addRenderableWidget(
                Button.builder(
                        Component.literal("MIRROR X"),
                        button -> mirrorX = !mirrorX
                ).bounds(center + 3, y, 97, 20).build()
        );

        y += 27;

        addRenderableWidget(
                Button.builder(
                        Component.literal("X -"),
                        button -> offsetX--
                ).bounds(center - 100, y, 47, 20).build()
        );

        addRenderableWidget(
                Button.builder(
                        Component.literal("X +"),
                        button -> offsetX++
                ).bounds(center - 49, y, 47, 20).build()
        );

        addRenderableWidget(
                Button.builder(
                        Component.literal("Y -"),
                        button -> offsetY--
                ).bounds(center + 3, y, 47, 20).build()
        );

        addRenderableWidget(
                Button.builder(
                        Component.literal("Y +"),
                        button -> offsetY++
                ).bounds(center + 54, y, 46, 20).build()
        );

        y += 25;

        addRenderableWidget(
                Button.builder(
                        Component.literal("Z -"),
                        button -> offsetZ--
                ).bounds(center - 100, y, 47, 20).build()
        );

        addRenderableWidget(
                Button.builder(
                        Component.literal("Z +"),
                        button -> offsetZ++
                ).bounds(center - 49, y, 47, 20).build()
        );

        addRenderableWidget(
                Button.builder(
                        Component.literal("AIR: SKIP"),
                        button -> {
                            replaceAir = !replaceAir;
                            button.setMessage(
                                    Component.literal(
                                            replaceAir
                                                    ? "AIR: REPLACE"
                                                    : "AIR: SKIP"
                                    )
                            );
                        }
                ).bounds(center + 3, y, 97, 20).build()
        );

        y += 29;

        addRenderableWidget(
                Button.builder(
                        Component.literal("PRINT INSTANTLY"),
                        button -> print()
                ).bounds(center - 100, y, 200, 25).build()
        );

        y += 32;

        addRenderableWidget(
                Button.builder(
                        Component.literal("CLOSE"),
                        button -> onClose()
                ).bounds(center - 100, y, 200, 20).build()
        );
    }

    private void loadFile() {

        try {

            JFileChooser chooser = new JFileChooser();

            chooser.setDialogTitle(
                    "Select Minecraft schematic"
            );

            chooser.setFileFilter(
                    new FileNameExtensionFilter(
                            "Sponge schematic (*.schem)",
                            "schem"
                    )
            );

            if (chooser.showOpenDialog(null)
                    != JFileChooser.APPROVE_OPTION) {
                return;
            }

            File file = chooser.getSelectedFile();

            loaded = SchematicReader.read(file.toPath());

            fileLabel = file.getName();

            status = "Loaded "
                    + loaded.width + " x "
                    + loaded.height + " x "
                    + loaded.length;

        } catch (Exception exception) {

            loaded = null;

            status = "Load error: "
                    + exception.getMessage();
        }
    }

    private void print() {

        Minecraft client = Minecraft.getInstance();

        if (loaded == null) {
            status = "Load a schematic first.";
            return;
        }

        if (client.player == null || client.level == null) {
            status = "Enter a world first.";
            return;
        }

        MinecraftServer server =
                client.getSingleplayerServer();

        if (server == null) {
            status = "Singleplayer printing only. Multiplayer needs server support.";
            return;
        }

        SchematicData data = loaded;

        int baseX = (int) Math.floor(client.player.getX())
                + offsetX;

        int baseY = (int) Math.floor(client.player.getY())
                + offsetY;

        int baseZ = (int) Math.floor(client.player.getZ())
                + offsetZ;

        int turns = rotation;
        boolean mirror = mirrorX;
        boolean air = replaceAir;

        var dimension = client.player.level().dimension();

        status = "Printing...";

        server.execute(() -> {

            ServerLevel level = server.getLevel(dimension);

            if (level == null) {
                return;
            }

            int placed = 0;
            int skipped = 0;

            for (int y = 0; y < data.height; y++) {

                for (int z = 0; z < data.length; z++) {

                    for (int x = 0; x < data.width; x++) {

                        BlockEntry entry =
                                data.blocks[
                                        (y * data.length + z)
                                                * data.width + x
                                ];

                        if (entry == null) {
                            skipped++;
                            continue;
                        }

                        if (!air && entry.state().isAir()) {
                            skipped++;
                            continue;
                        }

                        int mx = mirror
                                ? data.width - 1 - x
                                : x;

                        int rx;
                        int rz;

                        switch (turns) {

                            case 1 -> {
                                rx = data.length - 1 - z;
                                rz = mx;
                            }

                            case 2 -> {
                                rx = data.width - 1 - mx;
                                rz = data.length - 1 - z;
                            }

                            case 3 -> {
                                rx = z;
                                rz = data.width - 1 - mx;
                            }

                            default -> {
                                rx = mx;
                                rz = z;
                            }
                        }

                        int px = baseX + rx;
                        int py = baseY + y;
                        int pz = baseZ + rz;

                        if (py < level.getMinY()
                                || py >= level.getMaxY()) {
                            skipped++;
                            continue;
                        }

                        BlockPos position =
                                new BlockPos(px, py, pz);

                        if (level.setBlockAndUpdate(
                                position,
                                entry.state()
                        )) {
                            placed++;
                        } else {
                            skipped++;
                        }
                    }
                }
            }

            int finalPlaced = placed;
            int finalSkipped = skipped;

            client.execute(() -> {
                status = "Finished! Placed "
                        + finalPlaced
                        + " | Skipped "
                        + finalSkipped;
            });
        });
    }

    @Override
    public void render(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {

        renderBackground(
                graphics,
                mouseX,
                mouseY,
                partialTick
        );

        int center = width / 2;

        graphics.drawCenteredString(
                font,
                "NOVA SCHEMATIC PRINTER",
                center,
                height / 2 - 119,
                0x55DDFF
        );

        graphics.drawCenteredString(
                font,
                fileLabel,
                center,
                height / 2 - 105,
                0xFFFFFF
        );

        graphics.drawCenteredString(
                font,
                "Offset: X " + offsetX
                        + " Y " + offsetY
                        + " Z " + offsetZ
                        + " | Rotation: " + (rotation * 90) + "°",
                center,
                height / 2 - 93,
                0xBBDDEE
        );

        graphics.drawCenteredString(
                font,
                status,
                center,
                height / 2 + 116,
                0xFFE08A
        );

        super.render(
                graphics,
                mouseX,
                mouseY,
                partialTick
        );
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
