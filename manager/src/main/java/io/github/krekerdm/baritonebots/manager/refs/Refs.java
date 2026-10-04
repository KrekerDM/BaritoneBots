package io.github.krekerdm.baritonebots.manager.refs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.krekerdm.baritonebots.common.geom.Box;
import io.github.krekerdm.baritonebots.common.geom.Dims;
import io.github.krekerdm.baritonebots.common.geom.Pos;
import io.github.krekerdm.baritonebots.common.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Position references (SPEC §5.7e): wherever a position, container or box is taken, a reference may stand instead of
 * coordinates and is replaced when the work is dispatched. Pure, unit-tested; {@link RefResolver} fetches what a
 * {@link Context} needs (owner, automatic detection) and calls {@link #resolve}.
 * <ul>
 *   <li>positions / containers: {@code {"ref":"home"}}, {@code {"ref":"waypoint","name":…}}, {@code {"ref":"owner"}},
 *       {@code {"ref":"owner_look"}}, {@code {"ref":"bot","id":…}}, {@code {"ref":"auto"}};</li>
 *   <li>boxes: {@code {"ref":"area","name":…}}, {@code {"ref":"auto"}}, two references or positions as
 *       {@code [a, b]} or {@code {"a":…, "b":…}};</li>
 *   <li>container lists: each element a position or reference; {@code {"ref":"auto"}} (alone or as an element)
 *       expands to a list.</li>
 * </ul>
 */
public final class Refs {
    public static final String HOME = "home";
    public static final String WAYPOINT = "waypoint";
    public static final String OWNER = "owner";
    public static final String OWNER_LOOK = "owner_look";
    public static final String BOT = "bot";
    public static final String AUTO = "auto";
    public static final String AREA = "area";
    /** Reference kinds for positions (the panel offers them as pickers). */
    public static final List<String> POS_KINDS = List.of(HOME, WAYPOINT, OWNER, OWNER_LOOK, BOT, AUTO);
    /** Reference kinds for boxes besides two position references. */
    public static final List<String> BOX_KINDS = List.of(AREA, AUTO);

    // Catalog argument types that may hold references.
    public static final String T_POS = "pos";
    public static final String T_BOX = "box";
    public static final String T_CONTAINER = "container";
    public static final String T_CONTAINERS = "containers";

    private Refs() {
    }

    /** A position with its dimension (null = unknown). */
    public record Located(Pos pos, String dim) {
    }

    /** What the owner's bots see: position, looked-at block (null = none), dimension, facing. */
    public record Owner(Pos pos, Pos look, String lookBlockId, String dim, double yaw) {
    }

    /** Values a resolution may use; lookups return null when unknown. */
    public interface Context {
        /** The home waypoint (the bot's own home for bot tasks). */
        Located home();

        Located waypoint(String name);

        Located bot(String id);

        /** A world area: box + dimension. */
        AreaBox area(String name);

        /** The owner as seen by an online bot; null = no bot sees the owner. */
        Owner owner();

        /** Automatic detection for an argument: a {@link Pos}, {@link Box} or {@code List<Pos>}; null = nothing found. */
        Object auto(String arg, String type);

        /** Dimension the result must be in (the bot's / project's), or null for no check. */
        String dim();
    }

    /** An area of the world document. */
    public record AreaBox(Box box, String dim) {
    }

    /** A reference that cannot be resolved; {@code reason} is a lower_snake_case code for events and the API. */
    public static final class RefException extends RuntimeException {
        private final String reason;

        public RefException(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    public static boolean isRef(JsonElement e) {
        return e != null && e.isJsonObject() && e.getAsJsonObject().has("ref");
    }

    /** True when any value inside {@code e} (deep) is a reference. */
    public static boolean contains(JsonElement e) {
        if (e == null || e.isJsonNull() || e.isJsonPrimitive()) {
            return false;
        }
        if (isRef(e)) {
            return true;
        }
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                if (contains(x)) {
                    return true;
                }
            }
            return false;
        }
        for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
            if (contains(en.getValue())) {
                return true;
            }
        }
        return false;
    }

    /** Reference kinds used in the reference-capable arguments ({@code owner}, {@code auto}, ...). */
    public static Set<String> kinds(JsonObject args, Map<String, String> argTypes) {
        Set<String> out = new LinkedHashSet<>();
        for (String arg : argTypes.keySet()) {
            collect(args.get(arg), out);
        }
        return out;
    }

    /** Arguments whose value uses {@code {"ref":"auto"}} somewhere. */
    public static List<String> autoArgs(JsonObject args, Map<String, String> argTypes) {
        List<String> out = new ArrayList<>();
        for (String arg : argTypes.keySet()) {
            Set<String> k = new LinkedHashSet<>();
            collect(args.get(arg), k);
            if (k.contains(AUTO)) {
                out.add(arg);
            }
        }
        return out;
    }

    private static void collect(JsonElement e, Set<String> out) {
        if (e == null || e.isJsonNull() || e.isJsonPrimitive()) {
            return;
        }
        if (isRef(e)) {
            out.add(Json.getString(e.getAsJsonObject(), "ref", ""));
            return;
        }
        if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(x -> collect(x, out));
        } else {
            e.getAsJsonObject().entrySet().forEach(x -> collect(x.getValue(), out));
        }
    }

    /**
     * Copy of {@code args} with the references in the arguments listed in {@code argTypes} (name → catalog type)
     * replaced by coordinates.
     *
     * @throws RefException naming the first reference that cannot be resolved
     */
    public static JsonObject resolve(JsonObject args, Map<String, String> argTypes, Context ctx) {
        JsonObject out = args.deepCopy();
        for (Map.Entry<String, String> a : argTypes.entrySet()) {
            JsonElement v = out.get(a.getKey());
            if (v == null || v.isJsonNull() || !contains(v)) {
                continue;
            }
            JsonElement resolved = switch (a.getValue()) {
                case T_POS, T_CONTAINER -> Json.toTree(pos(v, a.getKey(), a.getValue(), ctx));
                case T_BOX -> Json.toTree(box(v, a.getKey(), ctx));
                case T_CONTAINERS -> containers(v, a.getKey(), ctx);
                default -> v;
            };
            out.add(a.getKey(), resolved);
        }
        return out;
    }

    /** A position or position reference. */
    public static Pos pos(JsonElement v, String arg, String type, Context ctx) {
        return locate(v, arg, type, ctx).pos();
    }

    /** A position or reference with its dimension (null when a plain position). */
    public static Located locate(JsonElement v, String arg, String type, Context ctx) {
        if (!isRef(v)) {
            Pos p = Pos.fromJson(v);
            if (p == null) {
                throw new RefException("bad_ref", arg + ": not a position or reference");
            }
            return new Located(p, null);
        }
        JsonObject r = v.getAsJsonObject();
        String kind = Json.getString(r, "ref", "");
        Located l = switch (kind) {
            case HOME -> require(ctx.home(), "ref_no_home", "there is no home waypoint yet");
            case WAYPOINT -> {
                String name = Json.getString(r, "name", "").trim();
                if (name.isEmpty()) {
                    throw new RefException("bad_ref", arg + ": waypoint reference without a name");
                }
                yield require(ctx.waypoint(name), "ref_no_waypoint", "waypoint '" + name + "' not found");
            }
            case BOT -> {
                String id = Json.getString(r, "id", "").trim();
                yield require(ctx.bot(id), "ref_no_bot", "bot '" + id + "' has no known position (offline?)");
            }
            case OWNER, OWNER_LOOK -> {
                Owner o = ctx.owner();
                if (o == null || o.pos() == null) {
                    throw new RefException("ref_no_owner", "no online bot sees the owner player");
                }
                if (OWNER_LOOK.equals(kind) && o.look() == null) {
                    throw new RefException("ref_no_look", "the owner is not looking at a block within 64 blocks");
                }
                yield new Located(OWNER.equals(kind) ? o.pos() : o.look(), o.dim());
            }
            case AUTO -> {
                Object a = ctx.auto(arg, type);
                if (a instanceof Pos p) {
                    yield new Located(p, null);
                }
                if (a instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Pos p) {
                    yield new Located(p, null);
                }
                throw new RefException("ref_auto_none", arg + ": nothing suitable found automatically");
            }
            default -> throw new RefException("bad_ref", arg + ": unknown reference '" + kind + "'");
        };
        checkDim(l.dim(), arg, ctx);
        return l;
    }

    /** A box, area / auto reference, or two positions / references. */
    public static Box box(JsonElement v, String arg, Context ctx) {
        if (isRef(v)) {
            JsonObject r = v.getAsJsonObject();
            String kind = Json.getString(r, "ref", "");
            if (AREA.equals(kind)) {
                String name = Json.getString(r, "name", "").trim();
                AreaBox a = ctx.area(name);
                if (a == null) {
                    throw new RefException("ref_no_area", "area '" + name + "' not found");
                }
                checkDim(a.dim(), arg, ctx);
                return a.box();
            }
            if (AUTO.equals(kind)) {
                if (ctx.auto(arg, T_BOX) instanceof Box b) {
                    return b;
                }
                throw new RefException("ref_auto_none", arg + ": nothing suitable found automatically");
            }
            throw new RefException("bad_ref", arg + ": '" + kind + "' is not a box reference (area, auto or two points)");
        }
        JsonElement a = null;
        JsonElement b = null;
        if (v.isJsonArray() && v.getAsJsonArray().size() == 2) {
            a = v.getAsJsonArray().get(0);
            b = v.getAsJsonArray().get(1);
        } else if (v.isJsonObject() && v.getAsJsonObject().has("a") && v.getAsJsonObject().has("b")) {
            a = v.getAsJsonObject().get("a");
            b = v.getAsJsonObject().get("b");
        }
        if (a == null) {
            throw new RefException("bad_ref", arg + ": a box needs two corners");
        }
        return new Box(pos(a, arg, T_POS, ctx), pos(b, arg, T_POS, ctx));
    }

    /** A list of container positions; {@code auto} expands to the detected list. */
    public static JsonArray containers(JsonElement v, String arg, Context ctx) {
        JsonArray out = new JsonArray();
        List<JsonElement> items = new ArrayList<>();
        if (v.isJsonArray()) {
            v.getAsJsonArray().forEach(items::add);
        } else {
            items.add(v);
        }
        for (JsonElement e : items) {
            if (isRef(e) && AUTO.equals(Json.getString(e.getAsJsonObject(), "ref", ""))) {
                Object a = ctx.auto(arg, T_CONTAINERS);
                if (!(a instanceof List<?> list) || list.isEmpty()) {
                    throw new RefException("ref_auto_none", arg + ": no suitable containers found automatically");
                }
                for (Object p : list) {
                    if (p instanceof Pos pos && !out.contains(Json.toTree(pos))) {
                        out.add(Json.toTree(pos));
                    }
                }
            } else {
                JsonElement p = Json.toTree(pos(e, arg, T_CONTAINER, ctx));
                if (!out.contains(p)) {
                    out.add(p); // the same container twice would be visited twice
                }
            }
        }
        return out;
    }

    /** {@code goto} takes {@code x/y/z}: a resolved {@code pos} argument becomes them. */
    public static JsonObject gotoCoordinates(JsonObject args) {
        Pos p = Pos.fromJson(args.get("pos"));
        if (p == null) {
            return args;
        }
        JsonObject out = args.deepCopy();
        out.remove("pos");
        out.addProperty("x", p.x());
        out.addProperty("y", p.y());
        out.addProperty("z", p.z());
        return out;
    }

    private static Located require(Located l, String reason, String message) {
        if (l == null || l.pos() == null) {
            throw new RefException(reason, message);
        }
        return l;
    }

    private static void checkDim(String refDim, String arg, Context ctx) {
        String want = ctx.dim();
        if (refDim != null && want != null && !Dims.normalize(refDim).equals(Dims.normalize(want))) {
            throw new RefException("ref_wrong_dim", arg + ": the reference is in " + Dims.normalize(refDim)
                    + ", not in " + Dims.normalize(want));
        }
    }
}
