
package nova.printer;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class NovaSchematicPrinterClient
        implements ClientModInitializer {

    private static KeyMapping openKey;

    @Override
    public void onInitializeClient() {

        KeyMapping.Category category =
                KeyMapping.Category.register(
                        Identifier.fromNamespaceAndPath(
                                "nova_schematic_printer",
                                "main"
                        )
                );

        openKey = KeyBindingHelper.registerKeyBinding(
                new KeyMapping(
                        "key.nova_schematic_printer.open",
                        InputConstants.Type.KEYSYM,
                        GLFW.GLFW_KEY_RIGHT_SHIFT,
                        category
                )
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            while (openKey.consumeClick()) {

                if (client.player != null) {
                    client.setScreen(new PrinterScreen());
                }
            }
        });
    }

    public static void message(String text) {

        Minecraft client = Minecraft.getInstance();

        if (client.player != null) {
            client.player.displayClientMessage(
                    Component.literal("[Nova Printer] " + text),
                    false
            );
        }
    }
}
