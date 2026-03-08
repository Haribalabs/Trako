package com.trako;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Trako — NFT tracker and arbitrage trade identifier.
 * Single-file application for tracking NFT transfers and detecting arbitrage opportunities.
 */
public final class Trako {

    private static final String CONFIG_FILENAME = "trako.conf";
    private static final String DATA_DIR = "trako_data";
    private static final String TRANSFERS_FILE = "transfers.jsonl";
    private static final String ARB_SIGNALS_FILE = "arb_signals.jsonl";
    private static final String COLLECTIONS_FILE = "collections.txt";
    private static final int MIN_PRICE_DELTA_BPS = 50;
    private static final int BPS_DENOM = 10000;
    private static final long ARB_WINDOW_MS = 360_000;
    private static final int MAX_RECENT_TRANSFERS = 10000;
    private static final int DEFAULT_RPC_TIMEOUT_MS = 15000;

    public static void main(String[] args) {
        Trako app = new Trako();
        int exit = app.run(args);
        System.exit(exit);
    }

    private final Path dataPath;
    private final List<TransferRecord> recentTransfers;
    private final List<ArbSignal> arbSignals;
    private final Set<String> trackedCollections;
    private final TrakoConfig config;
    private final Object transferLock;
    private final Object arbLock;

    public Trako() {
        this.dataPath = Paths.get(DATA_DIR);
        this.recentTransfers = new ArrayList<>();
        this.arbSignals = new ArrayList<>();
        this.trackedCollections = new HashSet<>();
        this.config = loadOrCreateConfig();
        this.transferLock = new Object();
        this.arbLock = new Object();
    }

    public int run(String[] args) {
        ensureDataDir();
        loadCollections();
        loadPersistedTransfers();
        loadPersistedArbSignals();

        if (args.length == 0) {
            printUsage();
            return 0;
        }

        String cmd = args[0].toLowerCase(Locale.ROOT);
        switch (cmd) {
            case "track":
                return cmdTrack(args);
            case "list":
                return cmdList(args);
            case "arb":
                return cmdArb(args);
            case "add-collection":
                return cmdAddCollection(args);
            case "remove-collection":
                return cmdRemoveCollection(args);
            case "stats":
                return cmdStats(args);
            case "export":
                return cmdExport(args);
            case "scan":
                return cmdScan(args);
            case "batch-track":
                return cmdBatchTrack(args);
            case "collections":
                return cmdListCollections(args);
            case "query-address":
                return cmdQueryByAddress(args);
            case "query-collection":
                return cmdQueryByCollection(args);
            case "query-token":
                return cmdQueryByToken(args);
            case "price-stats":
                return cmdPriceStats(args);
            case "dedup-arb":
                return cmdDedupArb(args);
            case "filter":
                return cmdFilter(args);
            case "summary":
                return cmdSummary(args);
            case "version":
                return cmdVersion(args);
            case "help":
                printUsage();
                return 0;
            default:
                System.err.println("Unknown command: " + cmd);
                printUsage();
                return 1;
        }
    }

    private void ensureDataDir() {
        try {
            if (!Files.exists(dataPath)) {
                Files.createDirectories(dataPath);
            }
        } catch (IOException e) {
            System.err.println("Failed to create data directory: " + e.getMessage());
        }
    }

    private TrakoConfig loadOrCreateConfig() {
        Path configPath = Paths.get(CONFIG_FILENAME);
        if (Files.exists(configPath)) {
            try {
                return TrakoConfig.fromFile(configPath);
            } catch (IOException e) {
                System.err.println("Config read failed, using defaults: " + e.getMessage());
            }
        }
        TrakoConfig def = new TrakoConfig();
        try {
            def.save(configPath);
        } catch (IOException e) {
            System.err.println("Could not write default config: " + e.getMessage());
        }
        return def;
    }

    private void loadCollections() {
        Path p = dataPath.resolve(COLLECTIONS_FILE);
        if (!Files.exists(p)) return;
        try {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            for (String line : lines) {
                String addr = line.trim().toLowerCase(Locale.ROOT);
                if (addr.startsWith("0x") && addr.length() == 42) {
                    trackedCollections.add(addr);
                }
            }
        } catch (IOException e) {
            System.err.println("Could not load collections: " + e.getMessage());
        }
    }

    private void saveCollections() {
        Path p = dataPath.resolve(COLLECTIONS_FILE);
        try {
            Files.write(p, new ArrayList<>(trackedCollections), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Could not save collections: " + e.getMessage());
        }
    }

    private void loadPersistedTransfers() {
        Path p = dataPath.resolve(TRANSFERS_FILE);
        if (!Files.exists(p)) return;
        try {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                TransferRecord r = TransferRecord.fromJson(line);
                if (r != null) {
                    synchronized (transferLock) {
                        if (recentTransfers.size() < MAX_RECENT_TRANSFERS) {
                            recentTransfers.add(r);
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Could not load transfers: " + e.getMessage());
        }
    }

    private void appendTransferToFile(TransferRecord r) {
        Path p = dataPath.resolve(TRANSFERS_FILE);
        try {
            Files.write(p, Collections.singletonList(r.toJson()), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Could not append transfer: " + e.getMessage());
        }
    }

    private void loadPersistedArbSignals() {
        Path p = dataPath.resolve(ARB_SIGNALS_FILE);
        if (!Files.exists(p)) return;
        try {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                ArbSignal s = ArbSignal.fromJson(line);
                if (s != null) {
                    synchronized (arbLock) {
                        arbSignals.add(s);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Could not load arb signals: " + e.getMessage());
        }
    }

    private void appendArbSignalToFile(ArbSignal s) {
        Path p = dataPath.resolve(ARB_SIGNALS_FILE);
        try {
            Files.write(p, Collections.singletonList(s.toJson()), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Could not append arb signal: " + e.getMessage());
        }
    }

    private int cmdTrack(String[] args) {
        if (args.length < 7) {
            System.err.println("Usage: trako track <collection> <tokenId> <from> <to> <priceWei> <txHash>");
            return 1;
        }
        String collection = args[1].toLowerCase(Locale.ROOT);
        String tokenId = args[2];
        String from = args[3].toLowerCase(Locale.ROOT);
        String to = args[4].toLowerCase(Locale.ROOT);
        BigInteger priceWei;
        try {
            priceWei = new BigInteger(args[5]);
        } catch (NumberFormatException e) {
            System.err.println("Invalid priceWei: " + args[5]);
            return 1;
        }
        String txHash = args[6];

        TransferRecord r = new TransferRecord(collection, tokenId, from, to, System.currentTimeMillis(), priceWei, txHash);
        synchronized (transferLock) {
            recentTransfers.add(r);
            while (recentTransfers.size() > MAX_RECENT_TRANSFERS) {
                recentTransfers.remove(0);
            }
        }
        appendTransferToFile(r);
        System.out.println("Tracked transfer: " + collection + " #" + tokenId + " " + from + " -> " + to + " " + priceWei + " wei");
        return 0;
    }

    private int cmdList(String[] args) {
        int limit = 50;
        if (args.length > 1) {
            try {
                limit = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {}
        }
        synchronized (transferLock) {
            int size = recentTransfers.size();
            int from = Math.max(0, size - limit);
            for (int i = size - 1; i >= from && i >= 0; i--) {
                TransferRecord r = recentTransfers.get(i);
                System.out.println(r.toShortString());
            }
        }
        return 0;
    }

    private int cmdArb(String[] args) {
        boolean scan = args.length > 1 && "scan".equalsIgnoreCase(args[1]);
        if (scan) {
            runArbScan();
        }
        synchronized (arbLock) {
            int limit = 50;
            if (args.length > 1 && !scan) {
                try {
                    limit = Integer.parseInt(args[1]);
                } catch (NumberFormatException ignored) {}
            }
            int size = arbSignals.size();
            int from = Math.max(0, size - limit);
            for (int i = size - 1; i >= from && i >= 0; i--) {
                System.out.println(arbSignals.get(i).toShortString());
            }
        }
        return 0;
    }

    private void runArbScan() {
        Map<String, List<TransferRecord>> byCollectionToken = new HashMap<>();
        synchronized (transferLock) {
            long cutoff = System.currentTimeMillis() - ARB_WINDOW_MS;
            for (TransferRecord r : recentTransfers) {
                if (r.timestampMs < cutoff) continue;
                if (!trackedCollections.isEmpty() && !trackedCollections.contains(r.collection)) continue;
                if (r.priceWei == null || r.priceWei.signum() <= 0) continue;
                String key = r.collection + ":" + r.tokenId;
                byCollectionToken.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            }
        }
        for (Map.Entry<String, List<TransferRecord>> e : byCollectionToken.entrySet()) {
            List<TransferRecord> list = e.getValue();
            if (list.size() < 2) continue;
            list.sort(Comparator.comparingLong(x -> x.timestampMs));
            for (int i = 0; i < list.size() - 1; i++) {
                TransferRecord a = list.get(i);
                TransferRecord b = list.get(i + 1);
                if (b.timestampMs - a.timestampMs > ARB_WINDOW_MS) continue;
                BigInteger pa = a.priceWei;
                BigInteger pb = b.priceWei;
                BigInteger delta = pa.subtract(pb).abs();
                int deltaBps = pa.signum() == 0 ? 0 : delta.multiply(BigInteger.valueOf(BPS_DENOM)).divide(pa).intValue();
                if (deltaBps >= MIN_PRICE_DELTA_BPS) {
                    ArbSignal sig = new ArbSignal(a.collection, a.tokenId, a.timestampMs, b.timestampMs, pa, pb, deltaBps, a.txHash, b.txHash);
                    synchronized (arbLock) {
                        if (!arbSignals.contains(sig)) {
                            arbSignals.add(sig);
                            appendArbSignalToFile(sig);
                        }
                    }
                    System.out.println("Arb: " + sig.toShortString());
                }
            }
        }
    }

    private int cmdAddCollection(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: trako add-collection <address>");
            return 1;
        }
        String addr = args[1].trim().toLowerCase(Locale.ROOT);
        if (!addr.startsWith("0x") || addr.length() != 42) {
            System.err.println("Invalid address (need 0x + 40 hex)");
            return 1;
        }
        trackedCollections.add(addr);
        saveCollections();
        System.out.println("Added collection: " + addr);
        return 0;
    }

    private int cmdRemoveCollection(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: trako remove-collection <address>");
            return 1;
        }
        String addr = args[1].trim().toLowerCase(Locale.ROOT);
        trackedCollections.remove(addr);
        saveCollections();
        System.out.println("Removed collection: " + addr);
        return 0;
    }

    private int cmdStats(String[] args) {
        int transfers;
        int arbCount;
        int collections;
        synchronized (transferLock) {
            transfers = recentTransfers.size();
        }
        synchronized (arbLock) {
            arbCount = arbSignals.size();
        }
        collections = trackedCollections.size();
        System.out.println("Transfers in memory: " + transfers);
        System.out.println("Arb signals: " + arbCount);
        System.out.println("Tracked collections: " + collections);
        return 0;
    }

    private int cmdExport(String[] args) {
        String type = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "transfers";
        Path out = dataPath.resolve("export_" + type + "_" + System.currentTimeMillis() + ".jsonl");
        try {
