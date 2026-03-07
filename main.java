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
