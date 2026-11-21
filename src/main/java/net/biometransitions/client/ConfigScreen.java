package net.biometransitions.client;

import com.google.gson.JsonObject;
import net.biometransitions.config.BtgConfig;
import net.biometransitions.config.BtgConfig.Range;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Config editor built by reflection over {@link BtgConfig}: every number gets a slider plus a box for an
 * exact value, booleans a toggle, enums a cycle button, string lists a comma-separated box.
 */
public class ConfigScreen extends Screen {
    private static final String[] SECTIONS = {"General", "Boundary Detection", "Biome Mixing", "Biome Boundary Mixing",
            "Biome Replacing", "Biome Color Blending", "Biome Classification", "Performance", "Advanced", "Debug"};
    private static final int SIDEBAR = 130, ROW_H = 24;

    private final Screen parent;
    private final BtgConfig working = BtgConfig.get().copy();
    private int section = 0;
    private RowList list;
    private final java.util.Set<BtgConfig.PairRule> expanded = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    public ConfigScreen(Screen parent) {
        super(Component.literal("Biome Transition Generator"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        for (int i = 0; i < SECTIONS.length; i++) {
            int s = i;
            addRenderableWidget(Button.builder(Component.literal(SECTIONS[i]), b -> select(s))
                    .bounds(6, 28 + i * 21, SIDEBAR - 12, 20).build()).active = i != section;
        }
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).bounds(width / 2 - 104, height - 26, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(width / 2 + 4, height - 26, 100, 20).build());
        list = addRenderableWidget(new RowList(minecraft, width - SIDEBAR, height - 60, 26));
        list.setX(SIDEBAR);
        fill();
    }

    private void select(int s) {
        section = s;
        rebuildWidgets();
    }

    private void fill() {
        switch (section) {
            case 0 -> addObject(working.general);
            case 1 -> addObject(working.detection);
            case 2 -> {
                addObject(working.mixing, "surface");
                list.add(new Header("━━ Surface Compatibility & Surface Block Blending ━━"));
                list.add(new Note("Rules every mixed-in feature must meet; per category and per feature overrides are in JSON."));
                addObject(working.featureDefaults);
                list.add(new Header("Surface block blending"));
                addObject(working.mixing.surface);
            }
            case 3 -> pairs();
            case 4 -> addObject(working.replacing);
            case 5 -> {
                addField(working.general, "colorBlendDistance");
                addObject(working.colors);
            }
            case 6 -> {
                list.add(new Note("Groups: every biome gets groups from tags; special groups: all, surface, underground."));
                addObject(working.classification);
                list.add(new Header("Current tag rules (tags → groups)"));
                for (var r : working.classification.tagRules)
                    list.add(new Note(String.join(" + ", r.tags) + " → " + String.join(", ", r.categories)));
            }
            case 7 -> addObject(working.performance);
            case 8 -> {
                addObject(working.advanced);
                list.add(new Header("Feature rules"));
                addObject(working.features);
            }
            case 9 -> addObject(working.debug);
            default -> {}
        }
    }

    /** Biome Boundary Mixing: one expandable entry per pair of groups. */
    private void pairs() {
        list.add(new Note("Pairs not listed here mix by the global Biome Mixing settings. First matching pair wins."));
        for (var p : working.pairs) {
            boolean open = expanded.contains(p);
            String name = (open ? "▼ " : "▶ ") + (p.a.isBlank() ? "?" : p.a) + " + " + (p.b.isBlank() ? "?" : p.b) + "   [" + p.mode + "]";
            Button toggle = Button.builder(Component.literal(name), b -> {
                if (!expanded.remove(p)) expanded.add(p);
                rebuildWidgets();
            }).size(300, 20).build();
            Button remove = Button.builder(Component.literal("✕"), b -> {
                working.pairs.remove(p);
                rebuildWidgets();
            }).size(20, 20).build();
            remove.setTooltip(Tooltip.create(Component.literal("Remove this pair")));
            list.add(new Row("", List.of(toggle, remove), null, 0));
            if (open) addObject(p);
        }
        Button add = Button.builder(Component.literal("+ Add pair"), b -> {
            var p = new BtgConfig.PairRule();
            working.pairs.add(p);
            expanded.add(p);
            rebuildWidgets();
        }).size(120, 20).build();
        list.add(new Row("", List.of(add), null, 0));
    }

    private void save() {
        BtgConfig.save(working);
        BtgConfig.set(working);
        onClose();
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title.getString() + " — " + SECTIONS[section], width / 2, 9, 0xFFFFFF);
    }

    // ---- reflection ----

    private void addObject(Object o, String... skip) {
        for (Field f : o.getClass().getFields())
            if (!Modifier.isStatic(f.getModifiers()) && !Arrays.asList(skip).contains(f.getName())) addField(o, f);
    }

    private void addField(Object o, String name) {
        try {
            addField(o, o.getClass().getField(name));
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    private void addField(Object o, Field f) {
        Class<?> t = f.getType();
        String label = pretty(f.getName());
        String tip = tip(f);
        Range r = f.getAnnotation(Range.class);
        if (r != null && (t == int.class || t == double.class)) list.add(new NumberRow(label, o, f, r, tip));
        else if (t == boolean.class) list.add(new Row(label, List.of(toggle(o, f)), tip));
        else if (t.isEnum()) list.add(new Row(label, List.of(cycle(o, f, tip)), tip));
        else if (t == String.class) list.add(new Row(label, List.of(textBox(o, f, false)), tip));
        else if (List.class.isAssignableFrom(t) && listOf(f) != null) list.add(new Row(label, List.of(textBox(o, f, true)), tip));
        else if (Map.class.isAssignableFrom(t) || List.class.isAssignableFrom(t) || t == JsonObject.class)
            list.add(new Note(label + ": edit in config/biometransitions.json"));
        else if (t.getName().startsWith("net.biometransitions")) {
            list.add(new Header(label));
            addObject(get(o, f));
        }
    }

    private static String tip(Field f) {
        BtgConfig.Tip t = f.getAnnotation(BtgConfig.Tip.class);
        return t == null ? null : t.value();
    }

    /** Tooltip of an enum constant, or null. */
    private static String tip(Object constant) {
        if (constant == null) return "Use the global setting.";
        try {
            return tip(constant.getClass().getField(((Enum<?>) constant).name()));
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static Class<?> listOf(Field f) {
        var arg = ((ParameterizedType) f.getGenericType()).getActualTypeArguments()[0];
        return arg == String.class || arg == Double.class ? (Class<?>) arg : null;
    }

    private Button toggle(Object o, Field f) {
        return Button.builder(Component.literal(String.valueOf(get(o, f))), b -> {
            boolean v = !(Boolean) get(o, f);
            set(o, f, v);
            b.setMessage(Component.literal(String.valueOf(v)));
        }).size(80, 20).build();
    }

    /** Cycles the enum values; fields marked {@link BtgConfig.Inherit} also cycle through null = "Global". */
    private Button cycle(Object o, Field f, String fieldTip) {
        List<Object> values = new ArrayList<>();
        if (f.isAnnotationPresent(BtgConfig.Inherit.class)) values.add(null);
        values.addAll(Arrays.asList(f.getType().getEnumConstants()));
        Button button = Button.builder(Component.literal(name(get(o, f))), b -> {
            Object next = values.get((values.indexOf(get(o, f)) + 1) % values.size());
            set(o, f, next);
            b.setMessage(Component.literal(name(next)));
            b.setTooltip(optionTip(fieldTip, next));
        }).size(140, 20).build();
        button.setTooltip(optionTip(fieldTip, get(o, f)));
        return button;
    }

    private static String name(Object v) { return v == null ? "Global" : v.toString(); }

    private static Tooltip optionTip(String fieldTip, Object value) {
        String own = tip(value);
        String text = (fieldTip == null ? "" : fieldTip + "\n\n") + name(value) + (own == null ? "" : ": " + own) + "\n(click for the next option)";
        return Tooltip.create(Component.literal(text));
    }

    private EditBox textBox(Object o, Field f, boolean isList) {
        EditBox box = new EditBox(font, 0, 0, 260, 18, Component.literal(f.getName()));
        box.setMaxLength(4096);
        Object v = get(o, f);
        box.setValue(isList ? String.join(", ", ((List<?>) v).stream().map(String::valueOf).toList()) : String.valueOf(v));
        box.setResponder(s -> {
            if (!isList) {
                set(o, f, s.trim());
                return;
            }
            List<Object> out = new ArrayList<>();
            for (String part : s.split(",")) {
                part = part.trim();
                if (part.isEmpty()) continue;
                if (listOf(f) == Double.class) {
                    try { out.add(Double.parseDouble(part)); } catch (NumberFormatException e) { return; }
                } else out.add(part);
            }
            set(o, f, out);
        });
        return box;
    }

    private static Object get(Object o, Field f) {
        try { return f.get(o); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }

    private static void set(Object o, Field f, Object v) {
        try { f.set(o, v); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }

    static String pretty(String name) {
        String s = name.replaceAll("([a-z])([A-Z])", "$1 $2");
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- list + rows ----

    private static final class RowList extends ContainerObjectSelectionList<Row> {
        RowList(Minecraft mc, int width, int height, int y) { super(mc, width, height, y, ROW_H); }

        void add(Row r) { addEntry(r); }

        @Override
        public int getRowWidth() { return width - 24; }

        @Override
        protected int getScrollbarPosition() { return getX() + width - 8; }
    }

    private class Row extends ContainerObjectSelectionList.Entry<Row> {
        final String label;
        final List<AbstractWidget> widgets;
        final String tip;
        final int indent;

        Row(String label, List<AbstractWidget> widgets) { this(label, widgets, null); }

        Row(String label, List<AbstractWidget> widgets, String tip) { this(label, widgets, tip, 150); }

        /** @param indent where the widgets start, relative to the row */
        Row(String label, List<AbstractWidget> widgets, String tip, int indent) {
            this.label = label;
            this.widgets = widgets;
            this.tip = tip;
            this.indent = indent;
            if (tip != null)
                for (AbstractWidget wd : widgets) if (wd.getTooltip() == null) wd.setTooltip(Tooltip.create(Component.literal(tip)));
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int w, int h, int mouseX, int mouseY, boolean hovered, float pt) {
            g.drawString(font, label, left, top + 6, 0xE0E0E0);
            if (tip != null && hovered && mouseX < left + indent - 4)
                setTooltipForNextRenderPass(font.split(Component.literal(tip), 260));
            int x = left + indent;
            for (AbstractWidget wd : widgets) {
                wd.setPosition(x, top + (wd instanceof EditBox ? 1 : 0));
                wd.render(g, mouseX, mouseY, pt);
                x += wd.getWidth() + 4;
            }
        }

        @Override
        public List<? extends GuiEventListener> children() { return widgets; }

        @Override
        public List<? extends NarratableEntry> narratables() { return widgets; }
    }

    private class Header extends Row {
        Header(String label) { super(label, List.of()); }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int w, int h, int mouseX, int mouseY, boolean hovered, float pt) {
            g.drawString(font, "§e§l" + label, left, top + 10, 0xFFFFFF);
        }
    }

    private class Note extends Row {
        Note(String label) { super(label, List.of()); }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int w, int h, int mouseX, int mouseY, boolean hovered, float pt) {
            g.drawString(font, "§7" + label, left, top + 6, 0xFFFFFF);
        }
    }

    /** Slider + exact value box + "min – max unit". */
    private class NumberRow extends Row {
        private final Range range;
        private final boolean integer;
        private final Slider slider;
        private final EditBox box;

        NumberRow(String label, Object o, Field f, Range range, String tip) {
            super(label, new ArrayList<>(), tip);
            this.range = range;
            this.integer = f.getType() == int.class;
            double v = ((Number) get(o, f)).doubleValue();
            box = new EditBox(font, 0, 0, 56, 18, Component.literal(label));
            slider = new Slider(v, val -> {
                set(o, f, integer ? (Object) (int) Math.round(val) : (Object) val);
                String s = format(val);
                if (!box.getValue().equals(s) && !box.isFocused()) box.setValue(s);
            });
            box.setValue(format(v));
            box.setResponder(s -> {
                try {
                    double parsed = Math.max(range.min(), Math.min(range.max(), Double.parseDouble(s.trim())));
                    set(o, f, integer ? (Object) (int) Math.round(parsed) : (Object) parsed);
                    slider.setActual(parsed);
                } catch (NumberFormatException ignored) {
                    // keep the last valid value while typing
                }
            });
            widgets.add(slider);
            widgets.add(box);
            String limits = "\n\nLeft end: " + (range.minLabel().isEmpty() ? format(range.min()) : range.minLabel() + " (" + format(range.min()) + ")")
                    + "\nRight end: " + format(range.max()) + "\nType an exact value in the box.";
            Tooltip t = Tooltip.create(Component.literal((tip == null ? label : tip) + limits));
            slider.setTooltip(t);
            box.setTooltip(t);
        }

        String format(double v) {
            return integer ? String.valueOf(Math.round(v)) : String.format(java.util.Locale.ROOT, "%.2f", v);
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int w, int h, int mouseX, int mouseY, boolean hovered, float pt) {
            super.render(g, index, top, left, w, h, mouseX, mouseY, hovered, pt);
            String unit = range.unit().isEmpty() ? "" : " " + range.unit();
            g.drawString(font, "§8" + (range.minLabel().isEmpty() ? format(range.min()) : range.minLabel()) + " – " + format(range.max()) + unit, left + 150 + 160 + 4 + 56 + 6, top + 6, 0xFFFFFF);
        }

        private class Slider extends AbstractSliderButton {
            private final java.util.function.DoubleConsumer onChange;

            Slider(double actual, java.util.function.DoubleConsumer onChange) {
                super(0, 0, 160, 20, Component.empty(), 0);
                this.onChange = onChange;
                this.value = toSlider(actual);
                updateMessage();
            }

            double actual() {
                double v = range.min() + value * (range.max() - range.min());
                return integer ? Math.round(v) : v;
            }

            double toSlider(double v) { return (v - range.min()) / (range.max() - range.min()); }

            void setActual(double v) {
                value = Math.max(0, Math.min(1, toSlider(v)));
                updateMessage();
            }

            @Override
            protected void updateMessage() {
                String unit = range.unit().isEmpty() ? "" : " " + range.unit();
                boolean atMin = !range.minLabel().isEmpty() && actual() <= range.min();
                setMessage(Component.literal(atMin ? range.minLabel() : format(actual()) + unit));
            }

            @Override
            protected void applyValue() { onChange.accept(actual()); }
        }
    }
}
