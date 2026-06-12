package dev.jediscore.commands;

import dev.jediscore.datastructures.Bytes;
import dev.jediscore.datastructures.RedisValue;
import dev.jediscore.datastructures.ScoredMember;
import dev.jediscore.datastructures.SetValue;
import dev.jediscore.datastructures.ZSetValue;
import dev.jediscore.engine.CommandContext;
import dev.jediscore.engine.CommandException;
import dev.jediscore.engine.CommandRegistry;
import dev.jediscore.engine.CommandSpec;
import dev.jediscore.engine.Database;
import dev.jediscore.protocol.RespValue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sorted-set command family. Sorted sets use a {@code listpack} encoding
 * while small and a skiplist + hashmap once large; these commands operate
 * through {@link ZSetValue}, which exposes efficient rank/score/range primitives.
 *
 * <p>{@code ZSCAN} ships with the SCAN cursor family in a later phase.
 */
public final class ZSetCommands {

    private ZSetCommands() {
        // Static utility; not instantiable.
    }

    /**
     * Registers the sorted-set commands.
     *
     * @param registry the registry to populate
     */
    public static void registerAll(CommandRegistry registry) {
        registry.register(CommandSpec.of("zadd", -4, ZSetCommands::zadd));
        registry.register(CommandSpec.of("zincrby", 4, ZSetCommands::zincrby));
        registry.register(CommandSpec.of("zrem", -3, ZSetCommands::zrem));
        registry.register(CommandSpec.of("zscore", 3, ZSetCommands::zscore));
        registry.register(CommandSpec.of("zmscore", -3, ZSetCommands::zmscore));
        registry.register(CommandSpec.of("zcard", 2, ZSetCommands::zcard));
        registry.register(CommandSpec.of("zrank", -3, ctx -> zrankGeneric(ctx, false)));
        registry.register(CommandSpec.of("zrevrank", -3, ctx -> zrankGeneric(ctx, true)));
        registry.register(CommandSpec.of("zcount", 4, ZSetCommands::zcount));
        registry.register(CommandSpec.of("zlexcount", 4, ZSetCommands::zlexcount));
        registry.register(CommandSpec.of("zpopmin", -2, ctx -> zpop(ctx, false)));
        registry.register(CommandSpec.of("zpopmax", -2, ctx -> zpop(ctx, true)));
        registry.register(CommandSpec.of("zrange", -4, ZSetCommands::zrange));
        registry.register(CommandSpec.of("zrevrange", -4, ZSetCommands::zrevrange));
        registry.register(CommandSpec.of("zrangebyscore", -4, ctx -> zrangeByScore(ctx, false)));
        registry.register(CommandSpec.of("zrevrangebyscore", -4, ctx -> zrangeByScore(ctx, true)));
        registry.register(CommandSpec.of("zrangebylex", -4, ctx -> zrangeByLex(ctx, false)));
        registry.register(CommandSpec.of("zrevrangebylex", -4, ctx -> zrangeByLex(ctx, true)));
        registry.register(CommandSpec.of("zrangestore", -5, ZSetCommands::zrangestore));
        registry.register(CommandSpec.of("zunion", -3, ctx -> setOp(ctx, Op.UNION, false)));
        registry.register(CommandSpec.of("zinter", -3, ctx -> setOp(ctx, Op.INTER, false)));
        registry.register(CommandSpec.of("zdiff", -3, ctx -> setOp(ctx, Op.DIFF, false)));
        registry.register(CommandSpec.of("zunionstore", -4, ctx -> setOp(ctx, Op.UNION, true)));
        registry.register(CommandSpec.of("zinterstore", -4, ctx -> setOp(ctx, Op.INTER, true)));
        registry.register(CommandSpec.of("zdiffstore", -4, ctx -> setOp(ctx, Op.DIFF, true)));
        registry.register(CommandSpec.of("zintercard", -3, ZSetCommands::zintercard));
    }

    // ---- ZADD ---------------------------------------------------------------

    private static RespValue zadd(CommandContext ctx) {
        boolean nx = false;
        boolean xx = false;
        boolean gt = false;
        boolean lt = false;
        boolean ch = false;
        boolean incr = false;
        int i = 2;
        loop:
        for (; i < ctx.argCount(); i++) {
            switch (ctx.argUpper(i)) {
                case "NX" -> nx = true;
                case "XX" -> xx = true;
                case "GT" -> gt = true;
                case "LT" -> lt = true;
                case "CH" -> ch = true;
                case "INCR" -> incr = true;
                default -> {
                    break loop;
                }
            }
        }
        if (nx && xx) {
            throw new CommandException("ERR XX and NX options at the same time are not compatible");
        }
        if ((gt && nx) || (lt && nx) || (gt && lt)) {
            throw new CommandException(
                    "ERR GT, LT, and/or NX options at the same time are not compatible");
        }
        int remaining = ctx.argCount() - i;
        if (remaining == 0 || remaining % 2 != 0) {
            throw CommandException.syntax();
        }
        if (incr && remaining != 2) {
            throw new CommandException("ERR INCR option supports a single increment-element pair");
        }

        // Parse all scores up front so a bad score aborts before any mutation.
        int pairs = remaining / 2;
        double[] scores = new double[pairs];
        byte[][] members = new byte[pairs][];
        for (int p = 0; p < pairs; p++) {
            scores[p] = Keyspaces.parseScore(ctx.arg(i + p * 2));
            members[p] = ctx.arg(i + p * 2 + 1);
        }

        Bytes key = new Bytes(ctx.arg(1));
        Database db = ctx.database();
        ZSetValue zset = Keyspaces.asZSet(db.lookup(key));
        boolean fresh = zset == null;
        if (fresh) {
            zset = Keyspaces.newZSet(ctx);
        }

        int added = 0;
        int changed = 0;
        Double incrResult = null;
        boolean incrAborted = false;

        for (int p = 0; p < pairs; p++) {
            double score = scores[p];
            byte[] member = members[p];
            Double current = zset.score(member);

            if (incr) {
                if ((nx && current != null) || (xx && current == null)) {
                    incrAborted = true;
                    break;
                }
                double base = current == null ? 0 : current;
                double newScore = base + score;
                if (Double.isNaN(newScore)) {
                    throw new CommandException("ERR resulting score is not a number (NaN)");
                }
                if (current != null && ((gt && newScore <= current) || (lt && newScore >= current))) {
                    incrAborted = true;
                    break;
                }
                zset.put(member, newScore);
                incrResult = newScore;
                if (current == null) {
                    added++;
                }
                break;
            }

            if (nx && current != null) {
                continue;
            }
            if (xx && current == null) {
                continue;
            }
            if (current == null) {
                zset.put(member, score);
                added++;
            } else if (score != current) {
                if ((gt && score <= current) || (lt && score >= current)) {
                    continue;
                }
                zset.put(member, score);
                changed++;
            }
        }

        if (fresh) {
            if (zset.size() > 0) {
                db.put(key, zset);
            }
        }

        if (incr) {
            return incrAborted ? RespValue.NULL : RespValue.bulk(Keyspaces.formatScore(incrResult));
        }
        return RespValue.integer(ch ? added + changed : added);
    }

    private static RespValue zincrby(CommandContext ctx) {
        double increment = Keyspaces.parseScore(ctx.arg(2));
        Bytes key = new Bytes(ctx.arg(1));
        byte[] member = ctx.arg(3);
        Database db = ctx.database();
        ZSetValue zset = Keyspaces.asZSet(db.lookup(key));
        boolean fresh = zset == null;
        if (fresh) {
            zset = Keyspaces.newZSet(ctx);
        }
        Double current = zset.score(member);
        double newScore = (current == null ? 0 : current) + increment;
        if (Double.isNaN(newScore)) {
            throw new CommandException("ERR resulting score is not a number (NaN)");
        }
        zset.put(member, newScore);
        if (fresh) {
            db.put(key, zset);
        }
        return RespValue.bulk(Keyspaces.formatScore(newScore));
    }

    // ---- simple reads -------------------------------------------------------

    private static RespValue zrem(CommandContext ctx) {
        Bytes key = new Bytes(ctx.arg(1));
        Database db = ctx.database();
        ZSetValue zset = Keyspaces.asZSet(db.lookup(key));
        if (zset == null) {
            return RespValue.integer(0);
        }
        int removed = 0;
        for (int i = 2; i < ctx.argCount(); i++) {
            if (zset.remove(ctx.arg(i))) {
                removed++;
            }
        }
        if (zset.size() == 0) {
            db.remove(key);
        }
        return RespValue.integer(removed);
    }

    private static RespValue zscore(CommandContext ctx) {
        ZSetValue zset = Keyspaces.asZSet(ctx.database().lookup(new Bytes(ctx.arg(1))));
        if (zset == null) {
            return RespValue.NULL;
        }
        Double score = zset.score(ctx.arg(2));
        return score == null ? RespValue.NULL : RespValue.bulk(Keyspaces.formatScore(score));
    }

    private static RespValue zmscore(CommandContext ctx) {
        ZSetValue zset = Keyspaces.asZSet(ctx.database().lookup(new Bytes(ctx.arg(1))));
        List<RespValue> out = new ArrayList<>(ctx.argCount() - 2);
        for (int i = 2; i < ctx.argCount(); i++) {
            Double score = (zset == null) ? null : zset.score(ctx.arg(i));
            out.add(score == null ? RespValue.NULL : RespValue.bulk(Keyspaces.formatScore(score)));
        }
        return new RespValue.Array(out);
    }

    private static RespValue zcard(CommandContext ctx) {
        ZSetValue zset = Keyspaces.asZSet(ctx.database().lookup(new Bytes(ctx.arg(1))));
        return RespValue.integer(zset == null ? 0 : zset.size());
    }

    private static RespValue zrankGeneric(CommandContext ctx, boolean reverse) {
        boolean withScore = false;
        if (ctx.argCount() == 4 && ctx.argUpper(3).equals("WITHSCORE")) {
            withScore = true;
        } else if (ctx.argCount() > 3) {
            throw CommandException.syntax();
        }
        ZSetValue zset = Keyspaces.asZSet(ctx.database().lookup(new Bytes(ctx.arg(1))));
        byte[] member = ctx.arg(2);
        long rank = (zset == null) ? -1 : zset.rank(member, reverse);
        if (rank < 0) {
            return withScore ? RespValue.NULL : RespValue.NULL;
        }
        if (!withScore) {
            return RespValue.integer(rank);
        }
        return new RespValue.Array(List.of(
                RespValue.integer(rank),
                RespValue.bulk(Keyspaces.formatScore(zset.score(member)))));
    }

    // ---- counts -------------------------------------------------------------

    private static RespValue zcount(CommandContext ctx) {
        ZSetValue zset = Keyspaces.asZSet(ctx.database().lookup(new Bytes(ctx.arg(1))));
        ScoreRange range = ScoreRange.parse(ctx.arg(2), ctx.arg(3));
        if (zset == null) {
            return RespValue.integer(0);
        }
        long count = 0;
        for (ScoredMember m : zset.ascending()) {
            if (range.contains(m.score())) {
                count++;
            }
        }
        return RespValue.integer(count);
    }

    private static RespValue zlexcount(CommandContext ctx) {
        ZSetValue zset = Keyspaces.asZSet(ctx.database().lookup(new Bytes(ctx.arg(1))));
        LexRange range = LexRange.parse(ctx.arg(2), ctx.arg(3));
        if (zset == null) {
            return RespValue.integer(0);
        }
        long count = 0;
        for (ScoredMember m : zset.ascending()) {
            if (range.contains(m.member())) {
                count++;
            }
        }
        return RespValue.integer(count);
    }

    // ---- pops ---------------------------------------------------------------

    private static RespValue zpop(CommandContext ctx, boolean max) {
        Bytes key = new Bytes(ctx.arg(1));
        long count = 1;
        boolean hasCount = ctx.argCount() == 3;
        if (hasCount) {
            count = Keyspaces.parseLong(ctx.arg(2));
            if (count < 0) {
                throw new CommandException("ERR value is out of range, must be positive");
            }
        } else if (ctx.argCount() > 3) {
            throw new CommandException(
                    "ERR wrong number of arguments for '" + (max ? "zpopmax" : "zpopmin") + "' command");
        }
        Database db = ctx.database();
        ZSetValue zset = Keyspaces.asZSet(db.lookup(key));
        List<RespValue> out = new ArrayList<>();
        if (zset != null) {
            for (long i = 0; i < count && zset.size() > 0; i++) {
                ScoredMember m = max ? zset.popMax() : zset.popMin();
                out.add(RespValue.bulk(m.member()));
                out.add(RespValue.bulk(Keyspaces.formatScore(m.score())));
            }
            if (zset.size() == 0) {
                db.remove(key);
            }
        }
        return new RespValue.Array(out);
    }

    // ---- range family -------------------------------------------------------

    private enum RangeMode { INDEX, SCORE, LEX }

    private static RespValue zrange(CommandContext ctx) {
        RangeMode mode = RangeMode.INDEX;
        boolean rev = false;
        boolean withScores = false;
        boolean hasLimit = false;
        long offset = 0;
        long count = -1;
        for (int i = 4; i < ctx.argCount(); ) {
            String opt = ctx.argUpper(i);
            switch (opt) {
                case "BYSCORE" -> {
                    mode = RangeMode.SCORE;
                    i++;
                }
                case "BYLEX" -> {
                    mode = RangeMode.LEX;
                    i++;
                }
                case "REV" -> {
                    rev = true;
                    i++;
                }
                case "WITHSCORES" -> {
                    withScores = true;
                    i++;
                }
                case "LIMIT" -> {
                    if (i + 2 >= ctx.argCount()) {
                        throw CommandException.syntax();
                    }
                    hasLimit = true;
                    offset = Keyspaces.parseLong(ctx.arg(i + 1));
                    count = Keyspaces.parseLong(ctx.arg(i + 2));
                    i += 3;
                }
                default -> throw CommandException.syntax();
            }
        }
        if (hasLimit && mode == RangeMode.INDEX) {
            throw new CommandException(
                    "ERR syntax error, LIMIT is only supported in combination with either BYSCORE or BYLEX");
        }
        if (withScores && mode == RangeMode.LEX) {
            throw CommandException.syntax();
        }
        ZSetValue zset = Keyspaces.asZSet(ctx.database().lookup(new Bytes(ctx.arg(1))));
        List<ScoredMember> result =
                computeRange(zset, mode, ctx.arg(2), ctx.arg(3), rev, hasLimit, offset, count);
        return emit(result, withScores);
    }

    private static RespValue zrevrange(CommandContext ctx) {
        // ZREVRANGE key start stop [WITHSCORES] — index mode, reversed.
        boolean withScores = ctx.argCount() == 4 && ctx.argUpper(3).equals("WITHSCORES");
        if (ctx.argCount() > 4 || (ctx.argCount() == 4 && !withScores)) {
            throw CommandException.syntax();
        }
        ZSetValue zset = Keyspaces.asZSet(ctx.database().lookup(new Bytes(ctx.arg(1))));
        if (zset == null) {
            return new RespValue.Array(List.of());
        }
        long start = Keyspaces.parseLong(ctx.arg(2));
        long stop = Keyspaces.parseLong(ctx.arg(3));
        return emit(zset.rangeByIndex(start, stop, true), withScores);
    }

    private static RespValue zrangeByScore(CommandContext ctx, boolean reverse) {
        // ZRANGEBYSCORE key min max [...]; ZREVRANGEBYSCORE key max min [...]
        boolean withScores = false;
        boolean hasLimit = false;
        long offset = 0;
        long count = -1;
        for (int i = 4; i < ctx.argCount(); ) {
            String opt = ctx.argUpper(i);
            switch (opt) {
                case "WITHSCORES" -> {
                    withScores = true;
                    i++;
                }
                case "LIMIT" -> {
                    if (i + 2 >= ctx.argCount()) {
                        throw CommandException.syntax();
                    }
                    hasLimit = true;
                    offset = Keyspaces.parseLong(ctx.arg(i + 1));
                    count = Keyspaces.parseLong(ctx.arg(i + 2));
                    i += 3;
                }
                default -> throw CommandException.syntax();
            }
        }
        byte[] minArg = reverse ? ctx.arg(3) : ctx.arg(2);
        byte[] maxArg = reverse ? ctx.arg(2) : ctx.arg(3);
        ZSetValue zset = Keyspaces.asZSet(ctx.database().lookup(new Bytes(ctx.arg(1))));
        List<ScoredMember> result =
                computeRange(zset, RangeMode.SCORE, minArg, maxArg, reverse, hasLimit, offset, count);
        return emit(result, withScores);
    }

    private static RespValue zrangeByLex(CommandContext ctx, boolean reverse) {
        boolean hasLimit = false;
        long offset = 0;
        long count = -1;
        for (int i = 4; i < ctx.argCount(); ) {
            if (ctx.argUpper(i).equals("LIMIT")) {
                if (i + 2 >= ctx.argCount()) {
                    throw CommandException.syntax();
                }
                hasLimit = true;
                offset = Keyspaces.parseLong(ctx.arg(i + 1));
                count = Keyspaces.parseLong(ctx.arg(i + 2));
                i += 3;
            } else {
                throw CommandException.syntax();
            }
        }
        byte[] minArg = reverse ? ctx.arg(3) : ctx.arg(2);
        byte[] maxArg = reverse ? ctx.arg(2) : ctx.arg(3);
        ZSetValue zset = Keyspaces.asZSet(ctx.database().lookup(new Bytes(ctx.arg(1))));
        List<ScoredMember> result =
                computeRange(zset, RangeMode.LEX, minArg, maxArg, reverse, hasLimit, offset, count);
        return emit(result, false);
    }

    private static RespValue zrangestore(CommandContext ctx) {
        Bytes dest = new Bytes(ctx.arg(1));
        // ZRANGESTORE dst src start stop [BYSCORE|BYLEX] [REV] [LIMIT off cnt]
        RangeMode mode = RangeMode.INDEX;
        boolean rev = false;
        boolean hasLimit = false;
        long offset = 0;
        long count = -1;
        for (int i = 5; i < ctx.argCount(); ) {
            switch (ctx.argUpper(i)) {
                case "BYSCORE" -> {
                    mode = RangeMode.SCORE;
                    i++;
                }
                case "BYLEX" -> {
                    mode = RangeMode.LEX;
                    i++;
                }
                case "REV" -> {
                    rev = true;
                    i++;
                }
                case "LIMIT" -> {
                    if (i + 2 >= ctx.argCount()) {
                        throw CommandException.syntax();
                    }
                    hasLimit = true;
                    offset = Keyspaces.parseLong(ctx.arg(i + 1));
                    count = Keyspaces.parseLong(ctx.arg(i + 2));
                    i += 3;
                }
                default -> throw CommandException.syntax();
            }
        }
        Database db = ctx.database();
        ZSetValue src = Keyspaces.asZSet(db.lookup(new Bytes(ctx.arg(2))));
        List<ScoredMember> result =
                computeRange(src, mode, ctx.arg(3), ctx.arg(4), rev, hasLimit, offset, count);
        if (result.isEmpty()) {
            db.remove(dest);
            return RespValue.integer(0);
        }
        ZSetValue out = Keyspaces.newZSet(ctx);
        for (ScoredMember m : result) {
            out.put(m.member(), m.score());
        }
        db.put(dest, out);
        return RespValue.integer(out.size());
    }

    /** Computes a range over the (possibly null) sorted set per mode/options. */
    private static List<ScoredMember> computeRange(ZSetValue zset, RangeMode mode, byte[] startArg,
                                                   byte[] stopArg, boolean rev, boolean hasLimit,
                                                   long offset, long count) {
        if (mode == RangeMode.INDEX) {
            if (zset == null) {
                return List.of();
            }
            return zset.rangeByIndex(Keyspaces.parseLong(startArg), Keyspaces.parseLong(stopArg), rev);
        }
        // SCORE / LEX: filter ascending, optionally reverse, then apply LIMIT.
        List<ScoredMember> filtered = new ArrayList<>();
        if (zset != null) {
            if (mode == RangeMode.SCORE) {
                ScoreRange range = ScoreRange.parse(startArg, stopArg);
                for (ScoredMember m : zset.ascending()) {
                    if (range.contains(m.score())) {
                        filtered.add(m);
                    }
                }
            } else {
                LexRange range = LexRange.parse(startArg, stopArg);
                for (ScoredMember m : zset.ascending()) {
                    if (range.contains(m.member())) {
                        filtered.add(m);
                    }
                }
            }
        }
        if (rev) {
            java.util.Collections.reverse(filtered);
        }
        if (hasLimit) {
            filtered = applyLimit(filtered, offset, count);
        }
        return filtered;
    }

    private static List<ScoredMember> applyLimit(List<ScoredMember> list, long offset, long count) {
        if (offset < 0 || offset >= list.size()) {
            return List.of();
        }
        int from = (int) offset;
        int to = (count < 0) ? list.size() : (int) Math.min(list.size(), from + count);
        return new ArrayList<>(list.subList(from, to));
    }

    private static RespValue emit(List<ScoredMember> members, boolean withScores) {
        List<RespValue> out = new ArrayList<>(members.size() * (withScores ? 2 : 1));
        for (ScoredMember m : members) {
            out.add(RespValue.bulk(m.member()));
            if (withScores) {
                out.add(RespValue.bulk(Keyspaces.formatScore(m.score())));
            }
        }
        return new RespValue.Array(out);
    }

    // ---- ZUNION / ZINTER / ZDIFF (+ STORE) ----------------------------------

    private enum Op { UNION, INTER, DIFF }

    private enum Aggregate { SUM, MIN, MAX }

    private static RespValue setOp(CommandContext ctx, Op op, boolean store) {
        int idx = 1;
        Bytes dest = null;
        if (store) {
            dest = new Bytes(ctx.arg(idx++));
        }
        long numKeys = Keyspaces.parseLong(ctx.arg(idx++));
        if (numKeys <= 0) {
            throw new CommandException("ERR at least 1 input key is needed");
        }
        if (idx + numKeys > ctx.argCount()) {
            throw CommandException.syntax();
        }
        int firstKey = idx;
        int afterKeys = (int) (idx + numKeys);

        double[] weights = new double[(int) numKeys];
        java.util.Arrays.fill(weights, 1.0);
        Aggregate aggregate = Aggregate.SUM;
        boolean withScores = false;
        for (int i = afterKeys; i < ctx.argCount(); ) {
            String opt = ctx.argUpper(i);
            switch (opt) {
                case "WEIGHTS" -> {
                    if (op == Op.DIFF) {
                        throw CommandException.syntax();
                    }
                    if (i + numKeys >= ctx.argCount()) {
                        throw CommandException.syntax();
                    }
                    for (int w = 0; w < numKeys; w++) {
                        weights[w] = parseWeight(ctx.arg(i + 1 + w));
                    }
                    i += 1 + (int) numKeys;
                }
                case "AGGREGATE" -> {
                    if (op == Op.DIFF || i + 1 >= ctx.argCount()) {
                        throw CommandException.syntax();
                    }
                    aggregate = switch (ctx.argUpper(i + 1)) {
                        case "SUM" -> Aggregate.SUM;
                        case "MIN" -> Aggregate.MIN;
                        case "MAX" -> Aggregate.MAX;
                        default -> throw CommandException.syntax();
                    };
                    i += 2;
                }
                case "WITHSCORES" -> {
                    if (store) {
                        throw CommandException.syntax();
                    }
                    withScores = true;
                    i++;
                }
                default -> throw CommandException.syntax();
            }
        }

        Map<Bytes, Double> result = combine(ctx, op, firstKey, afterKeys, weights, aggregate);
        List<ScoredMember> ordered = sortByScore(result);

        if (!store) {
            return emit(ordered, withScores);
        }
        Database db = ctx.database();
        if (ordered.isEmpty()) {
            db.remove(dest);
            return RespValue.integer(0);
        }
        ZSetValue out = Keyspaces.newZSet(ctx);
        for (ScoredMember m : ordered) {
            out.put(m.member(), m.score());
        }
        db.put(dest, out);
        return RespValue.integer(out.size());
    }

    private static RespValue zintercard(CommandContext ctx) {
        long numKeys = Keyspaces.parseLong(ctx.arg(1));
        if (numKeys <= 0) {
            throw new CommandException("ERR numkeys should be greater than 0");
        }
        int firstKey = 2;
        int afterKeys = (int) (2 + numKeys);
        if (afterKeys > ctx.argCount()) {
            throw CommandException.syntax();
        }
        long limit = 0;
        if (afterKeys < ctx.argCount()) {
            if (!ctx.argUpper(afterKeys).equals("LIMIT") || afterKeys + 1 >= ctx.argCount()) {
                throw CommandException.syntax();
            }
            limit = Keyspaces.parseLong(ctx.arg(afterKeys + 1));
            if (limit < 0) {
                throw new CommandException("ERR LIMIT can't be negative");
            }
        }
        double[] weights = new double[(int) numKeys];
        java.util.Arrays.fill(weights, 1.0);
        Map<Bytes, Double> result = combine(ctx, Op.INTER, firstKey, afterKeys, weights, Aggregate.SUM);
        long card = result.size();
        if (limit > 0) {
            card = Math.min(card, limit);
        }
        return RespValue.integer(card);
    }

    private static Map<Bytes, Double> combine(CommandContext ctx, Op op, int firstKey, int afterKeys,
                                              double[] weights, Aggregate aggregate) {
        Map<Bytes, Double> result = new LinkedHashMap<>();
        if (op == Op.DIFF) {
            result.putAll(readScored(ctx, ctx.arg(firstKey)));
            for (int i = firstKey + 1; i < afterKeys; i++) {
                for (Bytes member : readScored(ctx, ctx.arg(i)).keySet()) {
                    result.remove(member);
                }
            }
            return result;
        }
        if (op == Op.UNION) {
            for (int i = firstKey; i < afterKeys; i++) {
                double weight = weights[i - firstKey];
                for (Map.Entry<Bytes, Double> e : readScored(ctx, ctx.arg(i)).entrySet()) {
                    double weighted = weightScore(e.getValue(), weight);
                    result.merge(e.getKey(), weighted, (a, b) -> aggregateScore(a, b, aggregate));
                }
            }
            return result;
        }
        // INTER: members present in every input.
        Map<Bytes, Double> first = readScored(ctx, ctx.arg(firstKey));
        List<Map<Bytes, Double>> others = new ArrayList<>();
        for (int i = firstKey + 1; i < afterKeys; i++) {
            others.add(readScored(ctx, ctx.arg(i)));
        }
        outer:
        for (Map.Entry<Bytes, Double> e : first.entrySet()) {
            double acc = weightScore(e.getValue(), weights[0]);
            for (int j = 0; j < others.size(); j++) {
                Double s = others.get(j).get(e.getKey());
                if (s == null) {
                    continue outer;
                }
                acc = aggregateScore(acc, weightScore(s, weights[j + 1]), aggregate);
            }
            result.put(e.getKey(), acc);
        }
        return result;
    }

    /** Reads a key as member→score: a zset directly, or a set with implicit score 1. */
    private static Map<Bytes, Double> readScored(CommandContext ctx, byte[] keyArg) {
        RedisValue v = ctx.database().lookup(new Bytes(keyArg));
        Map<Bytes, Double> map = new LinkedHashMap<>();
        if (v == null) {
            return map;
        }
        if (v instanceof ZSetValue z) {
            for (ScoredMember m : z.ascending()) {
                map.put(new Bytes(m.member()), m.score());
            }
        } else if (v instanceof SetValue s) {
            for (byte[] m : s.members()) {
                map.put(new Bytes(m), 1.0);
            }
        } else {
            throw CommandException.wrongType();
        }
        return map;
    }

    private static double weightScore(double score, double weight) {
        double v = score * weight;
        return Double.isNaN(v) ? 0 : v;
    }

    private static double aggregateScore(double a, double b, Aggregate aggregate) {
        return switch (aggregate) {
            case SUM -> {
                double sum = a + b;
                yield Double.isNaN(sum) ? 0 : sum;
            }
            case MIN -> Math.min(a, b);
            case MAX -> Math.max(a, b);
        };
    }

    private static double parseWeight(byte[] bytes) {
        try {
            return Keyspaces.parseScore(bytes);
        } catch (CommandException e) {
            throw new CommandException("ERR weight value is not a float");
        }
    }

    private static List<ScoredMember> sortByScore(Map<Bytes, Double> map) {
        List<ScoredMember> out = new ArrayList<>(map.size());
        for (Map.Entry<Bytes, Double> e : map.entrySet()) {
            out.add(new ScoredMember(e.getKey().array(), e.getValue()));
        }
        out.sort((x, y) -> {
            int c = Double.compare(x.score(), y.score());
            return c != 0 ? c : java.util.Arrays.compareUnsigned(x.member(), y.member());
        });
        return out;
    }

    // ---- range bound parsing ------------------------------------------------

    private record ScoreRange(double min, boolean minExcl, double max, boolean maxExcl) {
        static ScoreRange parse(byte[] minB, byte[] maxB) {
            double[] lo = bound(minB);
            double[] hi = bound(maxB);
            return new ScoreRange(lo[0], lo[1] != 0, hi[0], hi[1] != 0);
        }

        private static double[] bound(byte[] b) {
            boolean exclusive = b.length > 0 && b[0] == '(';
            byte[] rest = exclusive ? java.util.Arrays.copyOfRange(b, 1, b.length) : b;
            double value;
            try {
                value = Keyspaces.parseScore(rest);
            } catch (CommandException e) {
                throw new CommandException("ERR min or max is not a float");
            }
            return new double[] {value, exclusive ? 1 : 0};
        }

        boolean contains(double score) {
            boolean geMin = minExcl ? score > min : score >= min;
            boolean leMax = maxExcl ? score < max : score <= max;
            return geMin && leMax;
        }
    }

    private record LexRange(byte[] min, boolean minExcl, boolean minNegInf, boolean minPosInf,
                            byte[] max, boolean maxExcl, boolean maxNegInf, boolean maxPosInf) {
        static LexRange parse(byte[] minB, byte[] maxB) {
            return new LexRange(
                    value(minB), excl(minB), isNeg(minB), isPos(minB),
                    value(maxB), excl(maxB), isNeg(maxB), isPos(maxB));
        }

        private static boolean isNeg(byte[] b) {
            return b.length == 1 && b[0] == '-';
        }

        private static boolean isPos(byte[] b) {
            return b.length == 1 && b[0] == '+';
        }

        private static boolean excl(byte[] b) {
            return b.length > 0 && b[0] == '(';
        }

        private static byte[] value(byte[] b) {
            if (isNeg(b) || isPos(b)) {
                return new byte[0];
            }
            if (b.length > 0 && (b[0] == '[' || b[0] == '(')) {
                return java.util.Arrays.copyOfRange(b, 1, b.length);
            }
            throw new CommandException("ERR min or max not valid string range item");
        }

        boolean contains(byte[] member) {
            boolean geMin;
            if (minNegInf) {
                geMin = true;
            } else if (minPosInf) {
                geMin = false;
            } else {
                int c = java.util.Arrays.compareUnsigned(member, min);
                geMin = minExcl ? c > 0 : c >= 0;
            }
            boolean leMax;
            if (maxPosInf) {
                leMax = true;
            } else if (maxNegInf) {
                leMax = false;
            } else {
                int c = java.util.Arrays.compareUnsigned(member, max);
                leMax = maxExcl ? c < 0 : c <= 0;
            }
            return geMin && leMax;
        }
    }
}
