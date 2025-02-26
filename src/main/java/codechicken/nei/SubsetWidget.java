package codechicken.nei;

import codechicken.core.gui.GuiScrollSlot;
import codechicken.lib.gui.GuiDraw;
import codechicken.lib.vec.Rectangle4i;
import codechicken.nei.ItemList.AnyMultiItemFilter;
import codechicken.nei.ItemList.ItemsLoadedCallback;
import codechicken.nei.ItemList.NothingItemFilter;
import codechicken.nei.SearchField.ISearchProvider;
import codechicken.nei.api.API;
import codechicken.nei.api.ItemFilter;
import codechicken.nei.api.ItemFilter.ItemFilterProvider;
import codechicken.nei.api.ItemInfo;
import codechicken.nei.guihook.GuiContainerManager;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumChatFormatting;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

import static codechicken.nei.NEIClientUtils.translate;

public class SubsetWidget extends Button implements ItemFilterProvider, ItemsLoadedCallback, ISearchProvider
{
    public static ReentrantReadWriteLock hiddenItemLock = new ReentrantReadWriteLock();
    public static Lock writeLock = hiddenItemLock.writeLock();
    public static Lock readLock = hiddenItemLock.readLock();
    
    public static class SubsetState
    {
        int state = 2;
        ArrayList<ItemStack> items = new ArrayList<>();
    }

    public static class SubsetTag
    {
        protected class SubsetSlot extends GuiScrollSlot
        {
            public SubsetSlot() {
                super(0, 0, 0, 0);
                setSmoothScroll(false);
            }

            @Override
            public int getSlotHeight(int slot) {
                return 18;
            }

            @Override
            protected int getNumSlots() {
                return children.size()+state.items.size();
            }

            @Override
            protected void slotClicked(int slot, int button, int mx, int my, int count) {
                if(slot < sorted.size()) {
                    SubsetTag tag = sorted.get(slot);
                    if (NEIClientUtils.shiftKey())
                        codechicken.nei.LayoutManager.searchField.setText("@" + tag.fullname);
                    else if (button == 0 && count >= 2)
                        SubsetWidget.showOnly(tag);
                    else
                        SubsetWidget.setHidden(tag, button == 1);
                } else {
                    ItemStack item = state.items.get(slot-sorted.size());
                    if(NEIClientUtils.controlKey())
                        NEIClientUtils.cheatItem(item, button, -1);
                    else
                        SubsetWidget.setHidden(state.items.get(slot-sorted.size()), button == 1);
                }
            }

            @Override
            protected void drawSlot(int slot, int x, int y, int mx, int my, float frame) {
                int w = windowBounds().width;
                Rectangle4i r = new Rectangle4i(x, y, w, getSlotHeight(slot));
                if(slot < sorted.size()) {
                    SubsetTag tag = sorted.get(slot);
                    codechicken.nei.LayoutManager.getLayoutStyle().drawSubsetTag(tag.displayName(), x, y, r.w, r.h, tag.state.state, r.contains(mx, my));
                }
                else {
                    ItemStack stack = state.items.get(slot-sorted.size());
                    boolean hidden = SubsetWidget.isHidden(stack);

                    int itemx = w/2-8;
                    int itemy = 1;

                    codechicken.nei.LayoutManager.getLayoutStyle().drawSubsetTag(null, x, y, r.w, r.h, hidden ? 0 : 2, false);

                    GuiContainerManager.drawItem(x+itemx, y+itemy, stack);
                    if(new Rectangle4i(itemx, itemy, 16, 16).contains(mx, my))
                        SubsetWidget.hoverStack = stack;
                }
            }

            @Override
            public void drawOverlay(float frame) {
            }

            @Override
            public void drawBackground(float frame) {
                drawRect(x, y, x+width, y+height, 0xFF202020);
            }

            @Override
            public int scrollbarAlignment() {
                return -1;
            }

            @Override
            public void drawScrollbar(float frame) {
                if(hasScrollbar())
                    super.drawScrollbar(frame);
            }

            @Override
            public int scrollbarGuideAlignment() {
                return 0;
            }
        }

        public final String fullname;
        public final ItemFilter filter;
        public TreeMap<String, SubsetTag> children = new TreeMap<>();
        public List<SubsetTag> sorted = Collections.emptyList();
        private int childwidth;

        protected SubsetState state = new SubsetState();
        protected final SubsetSlot slot = new SubsetSlot();
        private SubsetTag selectedChild;
        private int visible;

        public SubsetTag(String fullname) {
            this(fullname, new NothingItemFilter());
        }

        public SubsetTag(String fullname, ItemFilter filter) {
            assert filter != null : "Filter cannot be null";
            this.fullname = EnumChatFormatting.getTextWithoutFormattingCodes(fullname);
            this.filter = filter;
        }

        public String displayName() {
            String translated = translate("subsets."+fullname);
            if(!translated.startsWith("nei."))
                return translated;

            int idx = fullname.lastIndexOf('.');
            return idx < 0 ? fullname : fullname.substring(idx + 1);
        }

        public String name() {
            int idx = fullname.indexOf('.');
            return idx < 0 ? fullname : fullname.substring(idx+1);
        }

        public String parent() {
            int idx = fullname.lastIndexOf('.');
            return idx < 0 ? null : fullname.substring(0, idx);
        }

        private SubsetTag getTag(String name) {
            int idx = name.indexOf('.');
            String childname = idx > 0 ? name.substring(0, idx) : name;
            SubsetTag child = children.get(childname.toLowerCase());
            if(child == null)
                return null;

            return idx > 0 ? child.getTag(name.substring(idx+1)) : child;
        }

        private void recacheChildren() {
            sorted = new ArrayList<>(children.values());
            childwidth = 0;
            for(SubsetTag tag : sorted)
                childwidth = Math.max(childwidth, tag.nameWidth()+2);
        }

        private void addTag(SubsetTag tag) {
            String name = fullname == null ? tag.fullname : tag.fullname.substring(fullname.length()+1);
            int idx = name.indexOf('.');

            if(idx < 0) {//add or replace tag
                SubsetTag prev = children.put(name.toLowerCase(), tag);
                if(prev != null) {//replaced, load children
                    tag.children = prev.children;
                    tag.sorted = prev.sorted;
                }
                recacheChildren();
            }
            else {
                String childname = name.substring(0, idx);
                SubsetTag child = children.get(childname.toLowerCase());
                if(child == null)
                    children.put(childname.toLowerCase(), child = new SubsetTag(fullname == null ? childname : fullname+'.'+childname));
                recacheChildren();
                child.addTag(tag);
            }
        }

        protected void cacheState() {
            state = SubsetWidget.getState(this);
            for(SubsetTag tag : sorted)
                tag.cacheState();
        }

        public void addFiltersWithItem(List<ItemFilter> filters, String itemSearch) {
            if(itemSearch == null) {
                addFilters(filters);
            } else {
                if(filter != null)
                    filters.add(new DoubleFilteredFilter(filter, itemSearch));

                for(SubsetTag child : sorted)
                    child.addFiltersWithItem(filters, itemSearch);
                
            }
        }
        
        public void addFilters(List<ItemFilter> filters) {
            if(filter != null)
                filters.add(filter);

            for(SubsetTag child : sorted)
                child.addFilters(filters);
        }

        public void search(List<SubsetTag> tags, Pattern p) {
            if(fullname != null && p.matcher(fullname.toLowerCase()).find())
                tags.add(this);
            else
                for(SubsetTag child : sorted)
                    child.search(tags, p);
        }

        public void updateVisiblity(int mx, int my) {
            if(selectedChild != null) {
                selectedChild.updateVisiblity(mx, my);
                if(!selectedChild.isVisible())
                    selectedChild = null;
            }

            if(slot.contains(mx, my) && (selectedChild == null || !selectedChild.contains(mx, my))) {
                int mslot = slot.getClickedSlot(my);
                if(mslot >= 0 && mslot < sorted.size()) {
                    SubsetTag mtag = sorted.get(mslot);
                    if(mtag != null) {
                        if(mtag != selectedChild && selectedChild != null)
                            selectedChild.setHidden();
                        selectedChild = mtag;
                        selectedChild.setVisible();
                    }
                }

                setVisible();
            }

            if(selectedChild == null)
                countdownVisible();
        }

        public void setHidden() {
            visible = 0;
            slot.mouseMovedOrUp(0, 0, 0);//cancel any scrolling
            if(selectedChild != null) {
                selectedChild.setHidden();
                selectedChild = null;
            }
        }

        public void setVisible() {
            visible = 10;
            cacheState();
        }

        private void countdownVisible() {
            if(visible > 0 && --visible == 0)
                setHidden();
        }

        public void resize(int x, int pwidth, int y) {
            int mheight = area.h;
            int dheight = area.y2()-y;
            int cheight = slot.contentHeight();
            int height = cheight;
            if(cheight > mheight) {
                y = area.y;
                height = mheight;
            } else if(cheight > dheight) {
                y = area.y2() - cheight;
            }

            height = height/slot.getSlotHeight(0)*slot.getSlotHeight(0);//floor to a multiple of slot height

            int width = childwidth;
            if(!state.items.isEmpty())
                width = Math.max(childwidth, 18);
            if(slot.contentHeight() > height)
                width+=slot.scrollbarDim().width;

            boolean fitLeft = x-width >= area.x1();
            boolean fitRight = x+width+pwidth <= area.x2();
            if(pwidth >= 0 ? !fitRight && fitLeft : !fitLeft)
                pwidth*=-1;//swap
            x += pwidth >= 0 ? pwidth : -width;

            slot.setSize(x, y, width, height);
            slot.setMargins(slot.hasScrollbar() ? slot.scrollbarDim().width : 0, 0, 0, 0);

            if(selectedChild != null) {
                y = slot.getSlotY(sorted.indexOf(selectedChild))-slot.scrolledPixels()+slot.y;
                selectedChild.resize(x, pwidth >= 0 ? width : -width, y);
            }
        }

        protected int nameWidth() {
            return Minecraft.getMinecraft().fontRenderer.getStringWidth(displayName());
        }

        public boolean isVisible() {
            return visible > 0;
        }

        public void draw(int mx, int my) {
            slot.draw(mx, my, 0);
            if(selectedChild != null)
                selectedChild.draw(mx, my);
        }

        public boolean contains(int px, int py) {
            return slot.contains(px, py) ||
                    selectedChild != null && selectedChild.contains(px, py);
        }

        public void mouseClicked(int mx, int my, int button) {
            if(selectedChild != null && selectedChild.contains(mx, my))
                selectedChild.mouseClicked(mx, my, button);
            else if(slot.contains(mx, my))
                slot.mouseClicked(mx, my, button);
        }

        public void mouseDragged(int mx, int my, int button, long heldTime) {
            slot.mouseDragged(mx, my, button, heldTime);
            if(selectedChild != null)
                selectedChild.mouseDragged(mx, my, button, heldTime);
        }

        public void mouseUp(int mx, int my, int button) {
            slot.mouseMovedOrUp(mx, my, button);
            if(selectedChild != null)
                selectedChild.mouseUp(mx, my, button);
        }

        public boolean mouseScrolled(int mx, int my, int scroll) {
            if(slot.hasScrollbar() && slot.contains(mx, my)) {
                slot.scroll(scroll);
                return true;
            }

            if(selectedChild != null && selectedChild.mouseScrolled(mx, my, scroll))
                return true;

            if(slot.hasScrollbar() && !contains(mx, my)) {
                slot.scroll(scroll);
                return true;
            }

            return false;
        }

        public boolean isScrolling() {
            return slot.isScrolling() ||
                    selectedChild != null && selectedChild.isScrolling();
        }
    }

    protected static final SubsetTag root = new SubsetTag(null);
    public static Rectangle4i area = new Rectangle4i();
    public static ItemStack hoverStack;

    private static HashMap<String, SubsetState> subsetState = new HashMap<>();
    /**
     * All operations on this variable should be synchronised.
     */
    private static final ItemStackSet hiddenItems = new ItemStackSet();
    private static final AtomicReference<NBTTagList> dirtyHiddenItems = new AtomicReference<>();

    public static SubsetState getState(SubsetTag tag) {
        SubsetState state = subsetState.get(tag.fullname);
        return state == null ? new SubsetState() : state;
    }

    public static void addTag(SubsetTag tag) {
        updateState.stop();
        synchronized (root) {
            root.addTag(tag);
            updateState.reallocate();
        }
    }

    public static SubsetTag getTag(String name) {
        return name == null ? root : root.getTag(name);
    }

    public static boolean isHidden(ItemStack item) {
        try {
            if(readLock.tryLock(5, TimeUnit.SECONDS)) {
                try {
                    return hiddenItems.contains(item);
                } finally {
                    readLock.unlock();
                }
            } else {
                NEIClientConfig.logger.error("Unable to obtain read lock in 'isHidden'");
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return false;
    }

    private static void _setHidden(SubsetTag tag, boolean hidden) {
        for(ItemStack item : getState(tag).items)
            _setHidden(item, hidden);
        for(SubsetTag child : tag.sorted)
            _setHidden(child, hidden);
    }

    private static void _setHidden(ItemStack item, boolean hidden) {
        if (hiddenItemLock.isWriteLockedByCurrentThread()) {
            if (hidden)
                hiddenItems.add(item);
            else
                hiddenItems.remove(item);
        }
    }

    public static void showOnly(SubsetTag tag) {
        try {
            if(writeLock.tryLock(5, TimeUnit.SECONDS)) {
                try {
                    for (ItemStack item : ItemList.items)
                        _setHidden(item, true);
                    setHidden(tag, false);
                } finally {
                    writeLock.unlock();
                }
            } else {
                NEIClientConfig.logger.error("Unable to obtain write lock in 'showOnly'");
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static void setHidden(SubsetTag tag, boolean hidden) {
        try {
            if(writeLock.tryLock(5, TimeUnit.SECONDS)) {
                try {
                    _setHidden(tag, hidden);
                    updateHiddenItems();
                } finally {
                    writeLock.unlock();
                }
            } else {
                NEIClientConfig.logger.error("Unable to obtain write lock in 'setHidden'");
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static void setHidden(ItemStack item, boolean hidden) {
        try {
            if(writeLock.tryLock(5, TimeUnit.SECONDS)) {
                try {
                    _setHidden(item, hidden);
                    updateHiddenItems();
                } finally {
                    writeLock.unlock();
                }
            } else {
                NEIClientConfig.logger.error("Unable to obtain write lock in 'setHidden'");
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public static void unhideAll() {
        try {
            if(writeLock.tryLock(5, TimeUnit.SECONDS)) {
                try {
                    hiddenItems.clear();
                    updateHiddenItems();
                } finally {
                    writeLock.unlock();
                }
            } else {
                NEIClientConfig.logger.error("Unable to obtain write lock in 'unhideAll'");
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static void updateHiddenItems() {
        prepareDirtyHiddenItems.restart();
        updateState.restart();
    }

    public static void loadHidden() {
        try {
            if(writeLock.tryLock(5, TimeUnit.SECONDS)) {
                try {
                    hiddenItems.clear();
                } finally {
                    writeLock.unlock();
                }
            } else {
                NEIClientConfig.logger.error("Unable to obtain write lock in 'loadHidden'");
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
            return;
        }

        List<ItemStack> itemList = new LinkedList<>();
        try {
            NBTTagList list = NEIClientConfig.world.nbt.getTagList("hiddenItems", 10);
            for(int i = 0; i < list.tagCount(); i++)
                itemList.add(ItemStack.loadItemStackFromNBT(list.getCompoundTagAt(i)));
        }
        catch (Exception e) {
            NEIClientConfig.logger.error("Error loading hiddenItems", e);
            return;
        }

        try {
            if(writeLock.tryLock(5, TimeUnit.SECONDS)) {
                try {
                    for (ItemStack item : itemList)
                        hiddenItems.add(item);
                } finally {
                    writeLock.unlock();
                }
            } else {
                NEIClientConfig.logger.error("Unable to obtain second write lock in 'loadHidden'");
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        updateState.restart();
    }

    private static void saveHidden() {
        NBTTagList list = dirtyHiddenItems.getAndSet(null);
        if(list != null) {
            NEIClientConfig.world.nbt.setTag("hiddenItems", list);
            NEIClientConfig.world.saveNBT();
        }
    }

    private static final RestartableTask prepareDirtyHiddenItems = new RestartableTask("NEI Subset Save Thread")
    {
        private List<ItemStack> getList() {
            try {
                if(readLock.tryLock(5, TimeUnit.SECONDS)) {
                    try {
                        return hiddenItems.values();
                    } finally {
                        readLock.unlock();
                    }
                } else {
                    NEIClientConfig.logger.error("Unable to obtain read lock in 'NEI Subset Save Thread'");
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return new ArrayList<>();
        }

        @Override
        public void execute() {
            NBTTagList list = new NBTTagList();
            for(ItemStack item : getList()) {
                if(interrupted()) return;
                NBTTagCompound tag = new NBTTagCompound();
                item.writeToNBT(tag);
                list.appendTag(tag);
            }
            dirtyHiddenItems.set(list);
        }
    };

    private static final UpdateStateTask updateState = new UpdateStateTask();
    private static class UpdateStateTask extends RestartableTask
    {
        private volatile boolean reallocate;

        public UpdateStateTask() {
            super("NEI Subset Item Allocation");
        }

        @Override
        public void clearTasks() {
            super.clearTasks();
            reallocate = false;
        }

        public synchronized void reallocate() {
            // System.out.println("NEI Subset - Reallocate");
            // Thread.dumpStack();
            reallocate = true;
            restart();
        }

        @Override
        public void execute() {
            // System.out.println("Executing NEI Subset Item Allocation");
            HashMap<String, SubsetState> state = new HashMap<>();
            List<SubsetTag> tags = new LinkedList<>();
            synchronized (root) {
                cloneStates(root, tags, state);
                if(interrupted()) return;
            }

            if(reallocate) {
                for (ItemStack item : ItemList.items) {
                    if(interrupted()) return;
                    if(ItemInfo.isHidden(item)) continue;
                    for (SubsetTag tag : tags)
                        if (tag.filter.matches(item))
                            state.get(tag.fullname).items.add(item);
                }
            }

            synchronized (root) {
                calculateVisibility(root, state);
                if(interrupted()) return;
            }

            subsetState = state;
            ItemList.updateFilter.restart();
        }

        private void cloneStates(SubsetTag tag, List<SubsetTag> tags, HashMap<String, SubsetState> state) {
            for(SubsetTag child : tag.sorted) {
                if(interrupted()) return;
                cloneStates(child, tags, state);
            }

            tags.add(tag);
            SubsetState sstate = new SubsetState();
            if(!reallocate)
                sstate.items = SubsetWidget.getState(tag).items;
            state.put(tag.fullname, sstate);
        }

        private void calculateVisibility(SubsetTag tag, Map<String, SubsetState> state) {
            SubsetState sstate = state.get(tag.fullname);
            int hidden = 0;
            for(SubsetTag child : tag.sorted) {
                if(interrupted()) return;
                calculateVisibility(child, state);
                int cstate = state.get(child.fullname).state;
                if(cstate == 1)
                    sstate.state = 1;
                else if(cstate == 0)
                    hidden++;
            }

            if(sstate.state == 1) return;

            List<ItemStack> items = sstate.items;
            for(ItemStack item : items) {
                if(interrupted()) return;
                if (isHidden(item))
                    hidden++;
            }

            if(hidden == tag.sorted.size()+items.size())
                sstate.state = 0;
            else if(hidden > 0)
                sstate.state = 1;
        }
    }

    private long lastclicktime;

    public SubsetWidget() {
        super("NEI Subsets");
        API.addItemFilter(this);
        API.addSearchProvider(this);
        ItemList.loadCallbacks.add(this);
    }

    @Override
    public String getRenderLabel() {
        return translate("inventory.item_subsets");
    }

    @Override
    public void draw(int mx, int my) {
        super.draw(mx, my);

        area.set(x, y + h, w, LayoutManager.searchField.y - h - y); //23 for the search box

        hoverStack = null;
        if (root.isVisible()) {
            root.resize(area.x, 0, area.y);
            root.cacheState();
            root.draw(mx, my);
        }
    }

    @Override
    public void update() {
        Point mouse = GuiDraw.getMousePosition();
        updateVisiblity(mouse.x, mouse.y);
        saveHidden();
    }

    private void updateVisiblity(int mx, int my) {
        if(!root.isVisible() || root.isScrolling())
            return;

        root.updateVisiblity(mx, my);

        if(!root.isVisible() && bounds().contains(mx, my))
            root.setVisible();
    }

    @Override
    public boolean contains(int px, int py) {
        return super.contains(px, py) ||
                root.isVisible() && root.contains(px, py);
    }

    @Override
    public boolean handleClick(int mx, int my, int button) {
        if(root.isVisible() && root.contains(mx, my)) {
            root.mouseClicked(mx, my, button);
            return true;
        }

        if(button == 0) {
            if(System.currentTimeMillis() - lastclicktime < 500)
                unhideAll();
            else
                root.setVisible();

            NEIClientUtils.playClickSound();
            lastclicktime = System.currentTimeMillis();
        }

        return true;
    }

    /**
     * Not called
     */
    @Override
    public boolean onButtonPress(boolean rightclick) {
        return false;
    }

    @Override
    public void mouseDragged(int mx, int my, int button, long heldTime) {
        if(root.isVisible())
            root.mouseDragged(mx, my, button, heldTime);
    }

    @Override
    public void mouseUp(int mx, int my, int button) {
        if(root.isVisible())
            root.mouseUp(mx, my, button);
    }

    @Override
    public boolean onMouseWheel(int i, int mx, int my) {
        return root.isVisible() && root.mouseScrolled(mx, my, -i);
    }

    @Override
    public void onGuiClick(int mx, int my) {
        if(!contains(mx, my))
            root.setHidden();
    }

    @Override
    public ItemStack getStackMouseOver(int mx, int my) {
        return hoverStack;
    }

    @Override
    public ItemFilter getFilter() {
        //synchronise access on hiddenItems
        return item -> {
            try {
                if(readLock.tryLock(5, TimeUnit.SECONDS)) {
                    try {
                        return !hiddenItems.matches(item);
                    } finally {
                        readLock.unlock();
                    }
                } else {
                    NEIClientConfig.logger.error("Unable to obtain read lock in 'getFilter'");
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return false;
        };
    }

    @Override
    public boolean isPrimary() {
        return true;
    }

    @Override
    public ItemFilter getFilter(String searchText) {
        if(!searchText.startsWith("@"))
            return null;

        searchText = searchText.substring(1);
        String itemSearch = null;
        if(searchText.contains("->")) {
            String[] split = searchText.split("->");
            searchText = split[0];
            if(split.length > 1) itemSearch = split[1];
        }
        
        AnyMultiItemFilter filter = new AnyMultiItemFilter();
        SubsetTag tag = getTag(searchText);
        if(tag != null) {
            // We've got an exact match
            tag.addFiltersWithItem(filter.filters, itemSearch);
        }
        else {
            // Try searching for a substring
            Pattern p = SearchField.getPattern(searchText);
            if(p == null) return null;

            List<SubsetTag> matching = new LinkedList<>();
            root.search(matching, p);
            if(matching.isEmpty()) return null;
            for(SubsetTag tag2 : matching) {
                tag2.addFiltersWithItem(filter.filters, itemSearch);
            }
        }
        
        return filter;
    }

    @Override
    public void itemsLoaded() {
        updateState.reallocate();
    }
}
