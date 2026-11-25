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
import net.minecraft.client.resources.language.I18n;
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
 * <p>
 * All text comes from the language files ({@code assets/biometransitions/lang}): field labels from
 * {@code biometransitions.config.<class>.<field>}, tooltips from the same key + {@code .tooltip}, enum
 * constants from {@code biometransitions.config.enum.<enum>.<constant>}, everything else from
 * {@code biometransitions.gui.*}.
 */
public class ConfigScreen extends Screen {
    /** Section ids; names come from {@code biometransitions.gui.section.<id>}. */
    private static final String[] SECTIONS = {"general", "mixing", "pairs", "replacing", "colors", "classification",
            "performance", "advanced", "debug"};
    private static final String P = "biometransitions.";
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
        super(Component.translatable(P + "gui.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        for (int i = 0; i < SECTIONS.length; i++) {
            int s = i;
            addRenderableWidget(Button.builder(gui("section." + SECTIONS[i]), b -> select(s))
                    .bounds(6, 28 + i * 21, SIDEBAR - 12, 20).build()).active = i != section;
        }
        addRenderableWidget(Button.builder(gui("save"), b -> save()).bounds(width / 2 - 104, height - 26, 100, 20).build());
        addRenderableWidget(Button.builder(gui("cancel"), b -> onClose()).bounds(width / 2 + 4, height - 26, 100, 20).build());
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
                note("colors");
                addField(working.general, "colorBlendDistance");
                addObject(working.colors);
            }
            case 5 -> {
                note("classification");
                addObject(working.classification);
                list.add(new Header(text("gui.header.tag_rules")));
                for (var r : working.classification.tagRules)
                    list.add(new Note(text("gui.tag_rule", String.join(" + ", r.tags), String.join(", ", r.categories))));
            }
            case 6 -> addObject(working.performance);
            case 7 -> {
                addObject(working.advanced);
                list.add(new Header(text("gui.header.feature_rules")));
                addObject(working.features);
            }
            case 8 -> addObject(working.debug);
            default -> {}
        }
    }

    private void mixing() {
        var m = working.mixing;
        header("distances");
        note("distances");
        addField(working.general, "mixingDistance");
        addField(m, "treeMixingDistance");
        addField(m, "similarDistanceMultiplier");

        header("density");
        note("density");
        addObject(m.density);

        header("distribution");
        note("distribution.what");
        note("distribution.curve");
        note("distribution.settings");
        note("hover");
        addObject(m.distribution);

        header("mixing_rules");
        note("mixing_rules");
        addObject(m, "density", "distribution", "surface", "treeMixingDistance", "similarDistanceMultiplier");

        header("surface_compatibility");
        note("surface_compatibility");
        addObject(working.featureDefaults);
        header("surface_blending");
        note("surface_blending");
        addObject(m.surface);
    }

    private void replacing() {
        var r = working.replacing;
        header("small_biome_detection");
        note("small_biome_detection");
        addField(r, "smallBiomeDetection");
        addField(r, "overlapMultiplier");
        header("replacing");
        addObject(r, "smallBiomeDetection", "overlapMultiplier");
    }

    /** Biome Boundary Mixing: one expandable entry per pair of groups. */
    private void pairs() {
        note("pairs");
        for (var p : working.pairs) {
            boolean open = expanded.contains(p);
            Component name = Component.translatable(P + (open ? "gui.pair.open" : "gui.pair.closed"),
                    p.a.isBlank() ? "?" : p.a, p.b.isBlank() ? "?" : p.b, name(p.mode), name(p.blockDirection));
            Button toggle = Button.builder(name, b -> {
                if (!expanded.remove(p)) expanded.add(p);
                refresh();
            }).size(300, 20).build();
            Button remove = Button.builder(gui("pair.remove"), b -> {
                working.pairs.remove(p);
                refresh();
            }).size(20, 20).build();
            remove.setTooltip(Tooltip.create(gui("pair.remove.tooltip")));
            list.add(new Row("", List.of(toggle, remove), null, null, 0));
            if (open) addObject(p);
        }
        Button add = Button.builder(gui("pair.add"), b -> {
            var p = new BtgConfig.PairRule();
            working.pairs.add(p);
            expanded.add(p);
            refresh();
        }).size(120, 20).build();
        Button reset = Button.builder(gui("pair.reset_all"), b -> {
            working.pairs = BtgConfig.defaultPairs();
            expanded.clear();
            refresh();
        }).size(120, 20).build();
        reset.setTooltip(Tooltip.create(gui("pair.reset_all.tooltip", DEFAULT_PAIRS.size())));
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
        g.drawCenteredString(font, text("gui.title.section", title.getString(), text("gui.section." + SECTIONS[section])), width / 2, 9, 0xFFFFFF);
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
            Button file = Button.builder(gui("edit_in_file"), b -> {}).size(140, 20).build();
            file.active = false;
            list.add(new Row(label, List.of(file), (tip == null ? "" : tip + "\n\n") + text("gui.edit_in_file.tooltip"), bind, LABEL_W));
        } else if (t.getName().startsWith("net.biometransitions")) {
            list.add(new Header(label));
            addObject(get(o, f));
        }
    }

    // ---- text ----

    /** Translated text of {@code biometransitions.<key>}. */
    static String text(String key, Object... args) { return I18n.get(P + key, args); }

    private static Component gui(String key, Object... args) { return Component.translatable(P + "gui." + key, args); }

    private void header(String id) { list.add(new Header(text("gui.header." + id))); }

    /** One wrapped note ({@code biometransitions.gui.note.<id>}), split into rows that fit the list. */
    private void note(String id) {
        for (FormattedCharSequence line : font.split(gui("note." + id), list.getRowWidth() - 4)) list.add(new Note(line));
    }

    /** {@code biometransitions.config.<class>.<field>}: label key of a setting, tooltip key = this + ".tooltip". */
    static String key(Field f) {
        return P + "config." + f.getDeclaringClass().getSimpleName().toLowerCase(Locale.ROOT) + "." + f.getName();
    }

    private static String label(Field f) {
        String k = key(f);
        return I18n.exists(k) ? I18n.get(k) : pretty(f.getName());
    }

    /** Field description + "Default: ..." */
    private static String tip(Field f, Binding bind) {
        String k = key(f) + ".tooltip";
        return (I18n.exists(k) ? I18n.get(k) : label(f)) + "\n\n" + text("gui.default", describe(bind.defaultValue(), f));
    }

    private static String enumKey(Object constant) {
        Enum<?> e = (Enum<?>) constant;
        return P + "config.enum." + e.getDeclaringClass().getSimpleName().toLowerCase(Locale.ROOT) + "." + e.name().toLowerCase(Locale.ROOT);
    }

    /** Tooltip of an enum constant, or null. */
    private static String tip(Object constant) {
        if (constant == null) return text("gui.inherit.tooltip");
        String k = enumKey(constant) + ".tooltip";
        return I18n.exists(k) ? I18n.get(k) : null;
    }

    /** Translated unit with a leading space, or "". */
    private static String unit(Range r) {
        return r == null || r.unit().isEmpty() ? "" : " " + text("config.unit." + r.unit());
    }

    /** Name of a special slider value ({@code biometransitions.config.special.<id>}). */
    private static String special(String id) { return text("config.special." + id); }

    /** A value as the GUI shows it. */
    private static String describe(Object v, Field f) {
        if (v == null) return text("gui.inherit");
        Range r = f.getAnnotation(Range.class);
        if (v instanceof Number n) {
            if (r != null && !r.minLabel().isEmpty() && n.doubleValue() <= r.min()) return special(r.minLabel());
            if (r != null && !r.maxLabel().isEmpty() && n.doubleValue() >= r.max()) return special(r.maxLabel());
            return (v instanceof Integer ? String.valueOf(n.intValue()) : trim(n.doubleValue())) + unit(r);
        }
        if (v instanceof Boolean b) return text(b ? "gui.true" : "gui.false");
        if (v instanceof Enum<?>) return name(v);
        if (v instanceof Collection<?> c) {
            if (c.isEmpty()) return text("gui.empty");
            if (listOf(f) == null) return text("gui.entries", c.size());
            String s = String.join(", ", c.stream().map(x -> x instanceof Double d ? trim(d) : String.valueOf(x)).toList());
            return s.length() > 120 ? s.substring(0, 117) + "..." : s;
        }
        if (v instanceof Map<?, ?> m) return m.isEmpty() ? text("gui.empty") : text("gui.entries", m.size());
        if (v instanceof JsonObject o) return o.size() == 0 ? text("gui.empty") : text("gui.entries", o.size());
        if (v instanceof String s) return s.isEmpty() ? text("gui.empty") : s;
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
        return Button.builder(Component.literal(describe(get(o, f), f)), b -> {
            boolean v = !(Boolean) get(o, f);
            set(o, f, v);
            b.setMessage(Component.literal(describe(v, f)));
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

    /** Display name of an enum value (null = "Global"). */
    private static String name(Object v) {
        if (v == null) return text("gui.inherit");
        String k = enumKey(v);
        return I18n.exists(k) ? I18n.get(k) : v.toString();
    }

    private static Tooltip optionTip(String fieldTip, Object value) {
        String own = tip(value);
        String selected = own == null ? text("gui.selected", name(value)) : text("gui.selected.tip", name(value), own);
        String text = (fieldTip == null ? "" : fieldTip + "\n\n") + selected + "\n" + text("gui.click_next");
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
                reset = Button.builder(gui("reset"), b -> {
                    binding.reset();
                    refresh();
                }).size(RESET_W, 20).build();
                reset.setTooltip(Tooltip.create(gui("reset.tooltip", describe(binding.defaultValue(), binding.field()))));
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
            g.drawString(font, changed ? text("gui.changed_label", label) : label, left, top + 6, changed ? CHANGED : LABEL);
            if (tip != null && hovered && mouseX < left + indent - 4) {
                String text = changed ? tip + "\n" + text("gui.changed") : tip;
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
            String limits = "\n\n" + text("gui.slider.left", end(range.min(), range.minLabel()))
                    + "\n" + text("gui.slider.right", end(range.max(), range.maxLabel())) + "\n" + text("gui.slider.exact");
            Tooltip t = Tooltip.create(Component.literal((tip == null ? label : tip) + limits));
            slider.setTooltip(t);
            box.setTooltip(t);
        }

        String format(double v) {
            return integer ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%.2f", v);
        }

        /** Slider end for the tooltip: the number, or "Name (number)" for a special value. */
        private String end(double v, String special) {
            return special.isEmpty() ? format(v) : text("gui.slider.special", special(special), format(v));
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int w, int h, int mouseX, int mouseY, boolean hovered, float pt) {
            super.render(g, index, top, left, w, h, mouseX, mouseY, hovered, pt);
            String min = range.minLabel().isEmpty() ? format(range.min()) : special(range.minLabel());
            String max = range.maxLabel().isEmpty() ? format(range.max()) : special(range.maxLabel());
            g.drawString(font, "§8" + text("gui.slider.range", min, max, unit(range)), widgetsEnd + 2, top + 6, 0xFFFFFF);
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
                double v = actual();
                boolean atMin = !range.minLabel().isEmpty() && v <= range.min(), atMax = !range.maxLabel().isEmpty() && v >= range.max();
                setMessage(Component.literal(atMin ? special(range.minLabel()) : atMax ? special(range.maxLabel()) : format(v) + unit(range)));
            }

            @Override
            protected void applyValue() { onChange.accept(actual()); }
        }
    }
}
