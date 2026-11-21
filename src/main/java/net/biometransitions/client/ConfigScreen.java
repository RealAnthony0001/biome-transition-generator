package net.biometransitions.client;

import com.google.gson.JsonObject;
import net.biometransitions.config.BtgConfig;
import net.biometransitions.config.BtgConfig.Range;
import net.minecraft.client.Minecraft;
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
import net.minecraft.util.FormattedCharSequence;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Config editor built by reflection over {@link BtgConfig}: every number gets a slider plus a box for an
 * exact value, booleans a toggle, enums a cycle button, string lists a comma-separated box. Every setting
 * has a reset button; settings that differ from their default are shown in yellow with a star.
 */
public class ConfigScreen extends Screen {
    private static final String[] SECTIONS = {"General", "Biome Mixing", "Biome Boundary Mixing", "Biome Replacing",
            "Biome Color Blending", "Biome Classification", "Performance", "Advanced", "Debug"};
    private static final int SIDEBAR = 130, ROW_H = 24, LABEL_W = 150, RESET_W = 20;
    private static final int LABEL = 0xE0E0E0, CHANGED = 0xFFE066;
    /** Freshly constructed objects per config class: the source of every default value. */
    private static final Map<Class<?>, Object> DEFAULTS = new ConcurrentHashMap<>();
    private static final List<BtgConfig.PairRule> DEFAULT_PAIRS = BtgConfig.defaultPairs();

    private final Screen parent;
    private final BtgConfig working = BtgConfig.get().copy();
    private int section = 0;
    private RowList list;
    /** Scroll position to restore after the next rebuild, or -1. */
    private double keepScroll = -1;
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
        if (keepScroll >= 0) list.setScrollAmount(keepScroll);
        keepScroll = -1;
    }

    private void select(int s) {
        section = s;
        rebuildWidgets();
    }

    /** Rebuild the rows (after a reset or expanding a pair) without jumping back to the top. */
    private void refresh() {
        keepScroll = list.getScrollAmount();
        rebuildWidgets();
    }

    private void fill() {
        switch (section) {
            case 0 -> addObject(working.general);
            case 1 -> mixing();
            case 2 -> pairs();
            case 3 -> replacing();
            case 4 -> {
                note("Grass, foliage and water colours blend smoothly across biome boundaries. Client side only; "
                        + "changes apply to the visible world immediately after Save.");
                addField(working.general, "colorBlendDistance");
                addObject(working.colors);
            }
            case 5 -> {
                note("Groups: every biome gets groups from tags; special groups: all, surface, underground.");
                addObject(working.classification);
                list.add(new Header("Current tag rules (tags → groups)"));
                for (var r : working.classification.tagRules)
                    list.add(new Note(String.join(" + ", r.tags) + " → " + String.join(", ", r.categories)));
            }
            case 6 -> addObject(working.performance);
            case 7 -> {
                addObject(working.advanced);
                list.add(new Header("Feature rules"));
                addObject(working.features);
            }
            case 8 -> addObject(working.debug);
            default -> {}
        }
    }

    private void mixing() {
        var m = working.mixing;
        list.add(new Header("Distances"));
        note("How far a neighbour's features reach into a biome. Mixing Distance is the same setting as in General: "
                + "changing it here changes it there.");
        addField(working.general, "mixingDistance");
        addField(m, "treeMixingDistance");
        addField(m, "similarDistanceMultiplier");

        list.add(new Header("Density"));
        note("How many features the transition gets in total. Where they come from is decided by Distribution below.");
        addObject(m.density);

        list.add(new Header("Distribution"));
        note("Distribution decides which biome's features appear where inside the transition: the neighbour's share "
                + "of the features at every distance from the boundary.");
        note("One curve runs across the whole transition, from the zone edge in one biome over the boundary to the zone "
                + "edge in the other, and both biomes use it. Pairs mixing both ways meet 50/50 at the boundary; one-way "
                + "pairs (A into B) start at 100% of A right at the boundary and fade out inside B.");
        note("Curve and Falloff shape how the neighbour fades, Intensity and the two Influences set how strong it is, "
                + "Noise and Random Variation make it irregular. None of them moves the zone edge: that is the Mixing Distance.");
        note("Hover any setting for an explanation, an example and its default value.");
        addObject(m.distribution);

        list.add(new Header("Mixing rules"));
        note("Which biomes may mix at all. These lists win over every pair in Biome Boundary Mixing.");
        addObject(m, "density", "distribution", "surface", "treeMixingDistance", "similarDistanceMultiplier");

        list.add(new Header("━━ Surface Compatibility & Surface Block Blending ━━"));
        note("Rules every mixed-in feature must meet; per category and per feature overrides are in JSON.");
        addObject(working.featureDefaults);
        list.add(new Header("Surface block blending"));
        note("The neighbour's ground (sand, grass...) and snow spread into the transition in patches, so its plants can grow. "
                + "Turned on and off with Surface Mixing in General; pairs set their own direction and distance.");
        addObject(m.surface);
    }

    private void replacing() {
        var r = working.replacing;
        list.add(new Header("Small biome detection"));
        note("Measures biome areas, so too small ones can be replaced or lose their own features (formerly Boundary Detection).");
        addField(r, "smallBiomeDetection");
        addField(r, "overlapMultiplier");
        list.add(new Header("Replacing"));
        addObject(r, "smallBiomeDetection", "overlapMultiplier");
    }

    /** Biome Boundary Mixing: one expandable entry per pair of groups. */
    private void pairs() {
        note("Pairs not listed here mix by the global Biome Mixing settings. First matching pair wins. "
                + "Features and surface blocks have separate directions and distances.");
        for (var p : working.pairs) {
            boolean open = expanded.contains(p);
            String name = (open ? "▼ " : "▶ ") + (p.a.isBlank() ? "?" : p.a) + " + " + (p.b.isBlank() ? "?" : p.b)
                    + "   [" + p.mode + " | ground: " + p.blockDirection + "]";
            Button toggle = Button.builder(Component.literal(name), b -> {
                if (!expanded.remove(p)) expanded.add(p);
                refresh();
            }).size(300, 20).build();
            Button remove = Button.builder(Component.literal("✕"), b -> {
                working.pairs.remove(p);
                refresh();
            }).size(20, 20).build();
            remove.setTooltip(Tooltip.create(Component.literal("Remove this pair")));
            list.add(new Row("", List.of(toggle, remove), null, null, 0));
            if (open) addObject(p);
        }
        Button add = Button.builder(Component.literal("+ Add pair"), b -> {
            var p = new BtgConfig.PairRule();
            working.pairs.add(p);
            expanded.add(p);
            refresh();
        }).size(120, 20).build();
        Button reset = Button.builder(Component.literal("Reset all pairs"), b -> {
            working.pairs = BtgConfig.defaultPairs();
            expanded.clear();
            refresh();
        }).size(120, 20).build();
        reset.setTooltip(Tooltip.create(Component.literal("Replace the whole list with the default pairs ("
                + DEFAULT_PAIRS.size() + " realistic neighbours).")));
        list.add(new Row("", List.of(add, reset), null, null, 0));
    }

    private void save() {
        BtgConfig old = BtgConfig.get();
        BtgConfig.save(working);
        BtgConfig.set(working);
        if (colorsChanged(old, working) && minecraft != null && minecraft.level != null) {
            ColorBlending.refresh();
            minecraft.levelRenderer.allChanged(); // redraw with the new colours
        }
        onClose();
    }

    private static boolean colorsChanged(BtgConfig a, BtgConfig b) {
        return a.general.enabled != b.general.enabled || ColorBlending.radius(a) != ColorBlending.radius(b)
                || !BtgConfig.GSON.toJson(a.colors).equals(BtgConfig.GSON.toJson(b.colors));
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
        String label = label(f);
        Binding bind = new Binding(o, f);
        Range r = f.getAnnotation(Range.class);
        String tip = tip(f, bind);
        if (r != null && (t == int.class || t == double.class)) list.add(new NumberRow(label, bind, r, tip));
        else if (t == boolean.class) list.add(new Row(label, List.of(toggle(o, f)), tip, bind, LABEL_W));
        else if (t.isEnum()) list.add(new Row(label, List.of(cycle(o, f, tip)), tip, bind, LABEL_W));
        else if (t == String.class) list.add(new Row(label, List.of(textBox(o, f, false)), tip, bind, LABEL_W));
        else if (List.class.isAssignableFrom(t) && listOf(f) != null) list.add(new Row(label, List.of(textBox(o, f, true)), tip, bind, LABEL_W));
        else if (Map.class.isAssignableFrom(t) || List.class.isAssignableFrom(t) || t == JsonObject.class) {
            Button file = Button.builder(Component.literal("Edit in config file"), b -> {}).size(140, 20).build();
            file.active = false;
            list.add(new Row(label, List.of(file), (tip == null ? "" : tip + "\n\n") + "Edit in config/biometransitions.json.", bind, LABEL_W));
        } else if (t.getName().startsWith("net.biometransitions")) {
            list.add(new Header(label));
            addObject(get(o, f));
        }
    }

    /** One wrapped note, split into rows that fit the list. */
    private void note(String text) {
        for (FormattedCharSequence line : font.split(Component.literal(text), list.getRowWidth() - 4)) list.add(new Note(line));
    }

    private static String label(Field f) {
        BtgConfig.Label l = f.getAnnotation(BtgConfig.Label.class);
        return l != null ? l.value() : pretty(f.getName());
    }

    /** Field description + "Default: ..." */
    private static String tip(Field f, Binding bind) {
        BtgConfig.Tip t = f.getAnnotation(BtgConfig.Tip.class);
        String text = t == null ? label(f) : t.value();
        return text + "\n\nDefault: " + describe(bind.defaultValue(), f);
    }

    /** Tooltip of an enum constant, or null. */
    private static String tip(Object constant) {
        if (constant == null) return "Use the global setting.";
        try {
            BtgConfig.Tip t = constant.getClass().getField(((Enum<?>) constant).name()).getAnnotation(BtgConfig.Tip.class);
            return t != null ? t.value() : BtgConfig.externalTip(constant);
        } catch (NoSuchFieldException e) {
            return BtgConfig.externalTip(constant);
        }
    }

    /** A value as the GUI shows it. */
    private static String describe(Object v, Field f) {
        if (v == null) return "Global";
        Range r = f.getAnnotation(Range.class);
        if (v instanceof Number n) {
            String unit = r == null || r.unit().isEmpty() ? "" : " " + r.unit();
            if (r != null && !r.minLabel().isEmpty() && n.doubleValue() <= r.min()) return r.minLabel();
            return (v instanceof Integer ? String.valueOf(n.intValue()) : trim(n.doubleValue())) + unit;
        }
        if (v instanceof Collection<?> c) {
            if (c.isEmpty()) return "(empty)";
            if (listOf(f) == null) return c.size() + " entries";
            String s = String.join(", ", c.stream().map(x -> x instanceof Double d ? trim(d) : String.valueOf(x)).toList());
            return s.length() > 120 ? s.substring(0, 117) + "..." : s;
        }
        if (v instanceof Map<?, ?> m) return m.isEmpty() ? "(empty)" : m.size() + " entries";
        if (v instanceof JsonObject o) return o.size() == 0 ? "(empty)" : o.size() + " entries";
        if (v instanceof String s) return s.isEmpty() ? "(empty)" : s;
        return v.toString();
    }

    private static String trim(double d) {
        String s = String.format(Locale.ROOT, "%.2f", d);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    private static Class<?> listOf(Field f) {
        if (!(f.getGenericType() instanceof ParameterizedType pt)) return null;
        var arg = pt.getActualTypeArguments()[0];
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
        String text = (fieldTip == null ? "" : fieldTip + "\n\n") + "Selected: " + name(value) + (own == null ? "" : ": " + own) + "\n(click for the next option)";
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

    // ---- defaults ----

    /** The object holding the default values for {@code o}: a fresh instance, for pairs the matching default pair. */
    private static Object defaultsOf(Object o) {
        if (o instanceof BtgConfig.PairRule p) {
            for (var d : DEFAULT_PAIRS) if (d.a.equals(p.a) && d.b.equals(p.b)) return d;
            return new BtgConfig.PairRule();
        }
        return DEFAULTS.computeIfAbsent(o.getClass(), c -> {
            try {
                var ctor = c.getDeclaredConstructor();
                ctor.setAccessible(true);
                return ctor.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Config class without default constructor: " + c, e);
            }
        });
    }

    /** One setting: its value, its default, and resetting it. */
    private record Binding(Object owner, Field field) {
        Object value() { return get(owner, field); }

        Object defaultValue() { return get(defaultsOf(owner), field); }

        boolean modified() {
            Object v = value(), d = defaultValue();
            if (v instanceof Double a && d instanceof Double b) return Math.abs(a - b) > 1e-9;
            if (v == null || d == null || v instanceof Number || v instanceof Boolean || v instanceof String || v instanceof Enum<?>)
                return !Objects.equals(v, d);
            // lists, maps and nested objects (tag rules have no equals): compare their JSON form
            return !BtgConfig.GSON.toJsonTree(v, field.getGenericType()).equals(BtgConfig.GSON.toJsonTree(d, field.getGenericType()));
        }

        /** Restores the default, as a copy so later edits never touch the default object. */
        void reset() {
            Object d = defaultValue();
            Object copy = d == null || field.getType().isPrimitive() || field.getType().isEnum() || d instanceof String ? d
                    : BtgConfig.GSON.fromJson(BtgConfig.GSON.toJsonTree(d, field.getGenericType()), field.getGenericType());
            set(owner, field, copy);
        }
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
        final Binding binding;
        final Button reset;
        final int indent;
        /** x where the widgets ended in the last render pass. */
        int widgetsEnd;

        /**
         * @param binding the setting this row edits (null = no reset button)
         * @param indent  where the widgets start, relative to the row
         */
        Row(String label, List<AbstractWidget> widgets, String tip, Binding binding, int indent) {
            this.label = label;
            this.widgets = new ArrayList<>(widgets);
            this.tip = tip;
            this.binding = binding;
            this.indent = indent;
            if (tip != null)
                for (AbstractWidget wd : this.widgets) if (wd.getTooltip() == null) wd.setTooltip(Tooltip.create(Component.literal(tip)));
            if (binding != null) {
                reset = Button.builder(Component.literal("↺"), b -> {
                    binding.reset();
                    refresh();
                }).size(RESET_W, 20).build();
                reset.setTooltip(Tooltip.create(Component.literal("Reset to default: " + describe(binding.defaultValue(), binding.field()))));
            } else reset = null;
        }

        /** Adds a widget before the reset button (rows that build their widgets after the constructor). */
        void addWidget(AbstractWidget w) { widgets.add(w); }

        private List<AbstractWidget> all() {
            if (reset == null) return widgets;
            List<AbstractWidget> out = new ArrayList<>(widgets);
            out.add(reset);
            return out;
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int w, int h, int mouseX, int mouseY, boolean hovered, float pt) {
            boolean changed = binding != null && binding.modified();
            g.drawString(font, changed ? label + " *" : label, left, top + 6, changed ? CHANGED : LABEL);
            if (tip != null && hovered && mouseX < left + indent - 4) {
                String text = changed ? tip + "\n(changed from default)" : tip;
                setTooltipForNextRenderPass(font.split(Component.literal(text), 260));
            }
            if (reset != null) reset.active = changed;
            int x = left + indent;
            for (AbstractWidget wd : all()) {
                wd.setPosition(x, top + (wd instanceof EditBox ? 1 : 0));
                wd.render(g, mouseX, mouseY, pt);
                x += wd.getWidth() + 4;
            }
            widgetsEnd = x;
        }

        @Override
        public List<? extends GuiEventListener> children() { return all(); }

        @Override
        public List<? extends NarratableEntry> narratables() { return all(); }
    }

    private class Header extends Row {
        Header(String label) { super(label, List.of(), null, null, LABEL_W); }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int w, int h, int mouseX, int mouseY, boolean hovered, float pt) {
            g.drawString(font, "§e§l" + label, left, top + 10, 0xFFFFFF);
        }
    }

    private class Note extends Row {
        private final FormattedCharSequence text;

        Note(String label) { this(Component.literal(label).getVisualOrderText()); }

        Note(FormattedCharSequence text) {
            super("", List.of(), null, null, LABEL_W);
            this.text = text;
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int w, int h, int mouseX, int mouseY, boolean hovered, float pt) {
            g.drawString(font, text, left, top + 6, 0xA0A0A0);
        }
    }

    /** Slider + exact value box + reset + "min – max unit". */
    private class NumberRow extends Row {
        private final Range range;
        private final boolean integer;

        NumberRow(String label, Binding bind, Range range, String tip) {
            super(label, List.of(), tip, bind, LABEL_W);
            this.range = range;
            Object o = bind.owner();
            Field f = bind.field();
            this.integer = f.getType() == int.class;
            double v = ((Number) get(o, f)).doubleValue();
            EditBox box = new EditBox(font, 0, 0, 56, 18, Component.literal(label));
            Slider slider = new Slider(v, val -> {
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
            addWidget(slider);
            addWidget(box);
            String limits = "\n\nLeft end: " + (range.minLabel().isEmpty() ? format(range.min()) : range.minLabel() + " (" + format(range.min()) + ")")
                    + "\nRight end: " + format(range.max()) + "\nType an exact value in the box.";
            Tooltip t = Tooltip.create(Component.literal((tip == null ? label : tip) + limits));
            slider.setTooltip(t);
            box.setTooltip(t);
        }

        String format(double v) {
            return integer ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%.2f", v);
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int w, int h, int mouseX, int mouseY, boolean hovered, float pt) {
            super.render(g, index, top, left, w, h, mouseX, mouseY, hovered, pt);
            String unit = range.unit().isEmpty() ? "" : " " + range.unit();
            g.drawString(font, "§8" + (range.minLabel().isEmpty() ? format(range.min()) : range.minLabel()) + " – " + format(range.max()) + unit,
                    widgetsEnd + 2, top + 6, 0xFFFFFF);
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
