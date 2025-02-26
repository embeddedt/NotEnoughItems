package codechicken.nei.recipe;

import codechicken.core.TaskProfiler;
import codechicken.nei.ItemList;
import codechicken.nei.NEIClientConfig;
import codechicken.nei.NEIClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class GuiUsageRecipe extends GuiRecipe
{
    public static boolean openRecipeGui(String inputId, Object... ingredients) {
        Minecraft mc = NEIClientUtils.mc();
        GuiContainer prevscreen = mc.currentScreen instanceof GuiContainer ? (GuiContainer) mc.currentScreen : null;

        ArrayList<IUsageHandler> handlers;
        TaskProfiler profiler = ProfilerRecipeHandler.getProfiler();

        // Pre-find the fuels so we're not fighting over it
        FuelRecipeHandler.findFuelsOnceParallel();

        profiler.start("recipe.concurrent.usage");
        try {
            handlers = serialUsageHandlers.stream().map(h -> getUsageOrCatalystHandler(h, inputId, ingredients))
                .filter(h -> h.numRecipes() > 0)
                .collect(Collectors.toCollection(ArrayList::new));

            handlers.addAll(ItemList.forkJoinPool.submit(() -> usagehandlers.parallelStream()
                .map(h -> getUsageOrCatalystHandler(h, inputId, ingredients))
                .filter(h -> h.numRecipes() > 0)
                .collect(Collectors.toCollection(ArrayList::new))).get());
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
            EntityPlayer player = Minecraft.getMinecraft().thePlayer;
            if (player != null) {
                IChatComponent chat = new ChatComponentTranslation("nei.chat.recipe.error");
                chat.getChatStyle().setColor(EnumChatFormatting.RED);
                player.addChatComponentMessage(chat);
            }
            return false;
        } finally {
            profiler.end();
        }

        if (handlers.isEmpty())
            return false;

        handlers.sort(NEIClientConfig.HANDLER_COMPARATOR);

        BookmarkRecipeId recipeId = null;

        if (prevscreen instanceof GuiRecipe && ((GuiRecipe) prevscreen).recipeId != null) {
            recipeId = (((GuiRecipe) prevscreen).recipeId).copy();
        }

        GuiUsageRecipe gui = new GuiUsageRecipe(prevscreen, handlers, recipeId); 

        mc.displayGuiScreen(gui);

        if (!NEIClientUtils.shiftKey()) {
            gui.openTargetRecipe(recipeId);
        }

        return true;
    }

    private GuiUsageRecipe(GuiContainer prevgui, ArrayList<IUsageHandler> handlers, BookmarkRecipeId recipeId)
    {
        this(prevgui, handlers);
        this.recipeId = recipeId;
    }

    private GuiUsageRecipe(GuiContainer prevgui, ArrayList<IUsageHandler> handlers) {
        super(prevgui);
        currenthandlers = handlers;
    }

    public static void registerUsageHandler(IUsageHandler handler) {
        final String handlerId = handler.getHandlerId();
        if(usagehandlers.stream().anyMatch(h -> h.getHandlerId().equals(handlerId)) || serialUsageHandlers.stream().anyMatch(h -> h.getHandlerId().equals(handlerId)))
            return;

        if(NEIClientConfig.serialHandlers.contains(handlerId))
            serialUsageHandlers.add(handler);
        else
            usagehandlers.add(handler);
    }

    public ArrayList<? extends IRecipeHandler> getCurrentRecipeHandlers() {
        return currenthandlers;
    }

    private static IUsageHandler getUsageOrCatalystHandler(IUsageHandler handler, String inputId, Object... ingredients) {
        boolean skipCatalyst = NEIClientUtils.controlKey();
        if (NEIClientConfig.areJEIStyleRecipeCatalystsVisible() && !skipCatalyst) {
            return handler.getUsageAndCatalystHandler(inputId, ingredients);
        } else {
            return handler.getUsageHandler(inputId, ingredients);
        }
    }

    public ArrayList<IUsageHandler> currenthandlers;

    public static ArrayList<IUsageHandler> usagehandlers = new ArrayList<>();
    public static ArrayList<IUsageHandler> serialUsageHandlers = new ArrayList<>();
}
