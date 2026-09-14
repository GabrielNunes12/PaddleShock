package com.paddleshock.ui;

import java.io.IOException;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.HAlignment;
import com.simsilica.lemur.Insets3f;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.Panel;
import com.simsilica.lemur.TextField;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;

import com.paddleshock.GameConstants;
import com.paddleshock.app.PaddleShockApp;
import com.paddleshock.net.NetClient;
import com.paddleshock.net.NetHost;
import com.paddleshock.net.StunClient;
import com.paddleshock.net.TournamentClient;

/**
 * Live-bracket tournament screen, reached from {@link MultiplayerState}'s CHOICE view via its
 * TOURNAMENT button. Three sub-views: create-or-join a tournament, a waiting room while players
 * trickle in, and the bracket itself once the host starts it. Follows the same async patterns
 * established elsewhere in this redesigned UI - {@link LeaderboardState}'s generation-counter-
 * guarded background fetch for one-shot calls, {@link MultiplayerState}'s pattern for the
 * lobby-code create/join flow used to actually play each bracket pairing.
 *
 * <p>Orchestration only: the actual 1v1 match for each bracket pairing reuses {@code NetHost}/
 * {@code NetClient}/the existing lobby-code create-join flow completely unchanged (see
 * {@link #beginHostingBracketMatch}/{@link #beginJoiningBracketMatch}) - this class just tracks
 * which pairing is "mine" and publishes/consumes the lobby code for it via the tournament backend
 * (see {@code TournamentClient}), then hands off to {@link PaddleShockApp#enterHostedMatch}/
 * {@link PaddleShockApp#enterJoinedMatch} exactly like a normal internet match does.
 */
public class TournamentState extends BaseAppState {

    private enum View { CREATE_JOIN, WAITING, BRACKET }

    private static final float CARD_CONTENT_WIDTH = 380f;
    private static final float POLL_INTERVAL_SECONDS = 2.5f;
    private static final float JOIN_TIMEOUT_SECONDS = 20f;
    private static final float HELLO_RETRY_INTERVAL_SECONDS = 0.3f;

    private final Node uiRoot = new Node("tournamentUi");
    private View view = View.CREATE_JOIN;

    // ---- create/join view state ----
    private int selectedMaxPlayers = 4;
    private TextField codeField;
    private String createJoinError;
    private volatile boolean actionPending = false;
    private final AtomicInteger actionGeneration = new AtomicInteger(0);
    private final AtomicReference<TournamentClient.State> actionResult = new AtomicReference<>();
    private final AtomicReference<String> actionError = new AtomicReference<>();

    // ---- current tournament (waiting room + bracket) ----
    private String code;
    private TournamentClient.State state;
    private float pollTimer;
    private volatile boolean pollPending = false;
    private final AtomicInteger pollGeneration = new AtomicInteger(0);
    private final AtomicReference<TournamentClient.State> pollResult = new AtomicReference<>();
    private final AtomicReference<String> pollError = new AtomicReference<>();
    private String lastPollError;
    private volatile boolean startPending = false;
    private final AtomicReference<String> startError = new AtomicReference<>();

    // ---- hosting one bracket pairing (I'm p1) ----
    private NetHost matchNetHost;
    private int hostingRoundIndex = -1;
    private int hostingMatchIndex = -1;
    private final AtomicReference<String> matchLobbyCode = new AtomicReference<>();
    private final AtomicReference<String> matchHostError = new AtomicReference<>();
    private volatile boolean matchHostPending = false;
    private boolean matchHostResultShown = false;

    // ---- joining one bracket pairing (I'm p2) ----
    private NetClient matchNetClient;
    private int joiningRoundIndex = -1;
    private int joiningMatchIndex = -1;
    private String matchJoinError;
    private float matchJoinTimeoutTimer;
    private float matchHelloRetryTimer;

    @Override
    protected void initialize(Application application) {
        // Built fresh in rebuild() every time the screen is shown, the view changes, or a
        // background result lands.
    }

    // ============================== create / join ==============================

    private void buildCreateJoin(PaddleShockApp app, Container panel) {
        Label title = panel.addChild(new Label("TOURNAMENT"));
        title.setFontSize(26);
        title.setColor(Theme.BLUE);
        title.setInsets(new Insets3f(0, 0, 4, 0));

        Label sub = panel.addChild(new Label("Create a bracket for you and friends, or join one with a code."));
        sub.setFontSize(12);
        sub.setColor(Theme.TEXT_DIM);
        sub.setInsets(new Insets3f(0, 0, 18, 0));

        Label sizeLabel = panel.addChild(new Label("Tournament size"));
        sizeLabel.setFontSize(13);
        sizeLabel.setColor(Theme.TEXT);
        sizeLabel.setInsets(new Insets3f(0, 0, 6, 0));

        Container sizeRow = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        sizeRow.setInsets(new Insets3f(0, 0, 14, 0));
        Button size4 = sizeRow.addChild(new Button("4 PLAYERS"));
        styleToggle(size4, selectedMaxPlayers == 4);
        size4.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            selectedMaxPlayers = 4;
            rebuild();
        });
        Button size8 = sizeRow.addChild(new Button("8 PLAYERS"));
        size8.setInsets(new Insets3f(0, 0, 0, 10));
        styleToggle(size8, selectedMaxPlayers == 8);
        size8.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            selectedMaxPlayers = 8;
            rebuild();
        });

        Button create = panel.addChild(new Button("CREATE TOURNAMENT"));
        styleButton(create, Theme.ORANGE, Theme.ON_ACCENT, 17);
        create.setPreferredSize(new Vector3f(CARD_CONTENT_WIDTH, 48, 0));
        create.setInsets(new Insets3f(0, 0, 20, 0));
        create.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            beginCreate(app);
        });

        Panel divider = panel.addChild(new Panel());
        divider.setBackground(new QuadBackgroundComponent(Theme.PANEL_LINE));
        divider.setPreferredSize(new Vector3f(CARD_CONTENT_WIDTH, 1, 0));
        divider.setInsets(new Insets3f(0, 0, 18, 0));

        Label joinHint = panel.addChild(new Label("Have a code?"));
        joinHint.setFontSize(12);
        joinHint.setColor(Theme.TEXT_DIM);
        joinHint.setInsets(new Insets3f(0, 0, 6, 0));

        codeField = panel.addChild(new TextField(""));
        codeField.setFontSize(16);
        codeField.setColor(Theme.TEXT);
        codeField.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND_2));
        codeField.setPreferredWidth(CARD_CONTENT_WIDTH);
        codeField.setInsets(new Insets3f(8, 10, 8, 10));

        if (createJoinError != null) {
            Label errorLabel = panel.addChild(new Label(createJoinError));
            errorLabel.setFontSize(12);
            errorLabel.setColor(Theme.ORANGE);
            errorLabel.setInsets(new Insets3f(10, 0, 4, 0));
        }
        if (actionPending) {
            Label pending = panel.addChild(new Label("Working..."));
            pending.setFontSize(12);
            pending.setColor(Theme.TEXT_DIM);
            pending.setInsets(new Insets3f(10, 0, 4, 0));
        }

        Button join = panel.addChild(new Button("JOIN"));
        styleButton(join, Theme.PANEL_HOVER, Theme.TEXT, 16);
        join.setPreferredSize(new Vector3f(CARD_CONTENT_WIDTH, 46, 0));
        join.setInsets(new Insets3f(12, 0, 0, 0));
        join.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            beginJoin(app, codeField.getText().trim().toUpperCase(java.util.Locale.ROOT));
        });
    }

    private void styleToggle(Button button, boolean selected) {
        button.setBackground(new QuadBackgroundComponent(selected ? Theme.BLUE : Theme.PANEL_HOVER));
        button.setColor(selected ? Theme.ON_ACCENT : Theme.TEXT_DIM);
        button.setFontSize(13);
        button.setTextHAlignment(HAlignment.Center);
        button.setPreferredSize(new Vector3f(180, 40, 0));
        button.setInsets(new Insets3f(0, 0, 0, 0));
    }

    private void beginCreate(PaddleShockApp app) {
        if (actionPending) {
            return;
        }
        createJoinError = null;
        actionPending = true;
        actionResult.set(null);
        actionError.set(null);
        int myGeneration = actionGeneration.incrementAndGet();
        String hostPlayerId = app.getProfile().getPlayerId();
        String hint = shortId(hostPlayerId);
        int maxPlayers = selectedMaxPlayers;
        rebuild();
        Thread thread = new Thread(() -> {
            TournamentClient.State result = null;
            String error = null;
            try {
                String newCode = TournamentClient.createTournament(hostPlayerId, maxPlayers);
                // Register the host itself as a player - createTournament only returns the bare
                // code, so join immediately (idempotent server-side) to get the host into the
                // players list and pick up the full state in one round trip.
                result = TournamentClient.joinTournament(newCode, hostPlayerId, hint);
            } catch (IOException e) {
                error = e.getMessage() == null ? "the tournament service is unreachable" : e.getMessage();
            }
            if (actionGeneration.get() != myGeneration) {
                return; // superseded (screen left, or another action started) - discard
            }
            actionResult.set(result);
            actionError.set(error);
            actionPending = false;
        }, "tournament-create");
        thread.setDaemon(true);
        thread.start();
    }

    private void beginJoin(PaddleShockApp app, String enteredCode) {
        if (actionPending) {
            return;
        }
        if (enteredCode.isEmpty()) {
            createJoinError = "Enter a tournament code.";
            rebuild();
            return;
        }
        createJoinError = null;
        actionPending = true;
        actionResult.set(null);
        actionError.set(null);
        int myGeneration = actionGeneration.incrementAndGet();
        String playerId = app.getProfile().getPlayerId();
        String hint = shortId(playerId);
        rebuild();
        Thread thread = new Thread(() -> {
            TournamentClient.State result = null;
            String error = null;
            try {
                result = TournamentClient.joinTournament(enteredCode, playerId, hint);
            } catch (IOException e) {
                error = e.getMessage() == null ? "the tournament service is unreachable" : e.getMessage();
            }
            if (actionGeneration.get() != myGeneration) {
                return;
            }
            actionResult.set(result);
            actionError.set(error);
            actionPending = false;
        }, "tournament-join");
        thread.setDaemon(true);
        thread.start();
    }

    private static String shortId(String playerId) {
        String id = playerId == null ? "" : playerId.replace("-", "");
        return "Player-" + (id.length() >= 8 ? id.substring(0, 8) : id).toUpperCase();
    }

    // ============================== waiting room ==============================

    private void buildWaiting(PaddleShockApp app, Container panel) {
        boolean isHost = state != null && app.getProfile().getPlayerId().equals(state.getHostPlayerId());

        Label title = panel.addChild(new Label("TOURNAMENT LOBBY"));
        title.setFontSize(26);
        title.setColor(Theme.BLUE);
        title.setInsets(new Insets3f(0, 0, 4, 0));

        Container codeRow = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        codeRow.setInsets(new Insets3f(0, 0, 4, 0));
        Label codeLabel = codeRow.addChild(new Label("Code: " + code));
        codeLabel.setFontSize(20);
        codeLabel.setColor(Theme.ORANGE);
        Button copyButton = codeRow.addChild(new Button("COPY"));
        copyButton.setInsets(new Insets3f(0, 12, 0, 0));
        copyButton.setBackground(new QuadBackgroundComponent(Theme.PANEL_HOVER));
        copyButton.setColor(Theme.TEXT);
        copyButton.setFontSize(13);
        copyButton.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            copyToClipboard(code);
        });

        Label hint = panel.addChild(new Label(isHost ? "Share this code so others can join." : "Waiting on the host to start."));
        hint.setFontSize(12);
        hint.setColor(Theme.TEXT_DIM);
        hint.setInsets(new Insets3f(0, 0, 16, 0));

        int max = state == null ? selectedMaxPlayers : state.getMaxPlayers();
        List<TournamentClient.PlayerEntry> players = state == null ? List.of() : state.getPlayers();

        Container rows = panel.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        rows.setInsets(new Insets3f(0, 0, 12, 0));
        for (int i = 0; i < max; i++) {
            Container row = rows.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
            row.setInsets(new Insets3f(2, 0, 2, 0));
            if (i < players.size()) {
                TournamentClient.PlayerEntry entry = players.get(i);
                Label slot = row.addChild(new Label(entry.displayName()
                        + (entry.getPlayerId().equals(state.getHostPlayerId()) ? " (host)" : "")));
                slot.setFontSize(14);
                slot.setColor(Theme.TEXT);
            } else {
                Label slot = row.addChild(new Label("(waiting for a player...)"));
                slot.setFontSize(14);
                slot.setColor(Theme.TEXT_DIM2);
            }
        }

        if (pollError.get() != null && state == null) {
            Label errorLabel = panel.addChild(new Label("Couldn't load the lobby: " + pollError.get()));
            errorLabel.setFontSize(12);
            errorLabel.setColor(Theme.ORANGE);
            errorLabel.setInsets(new Insets3f(0, 0, 12, 0));
        }
        String startErr = startError.get();
        if (startErr != null) {
            Label errorLabel = panel.addChild(new Label("Couldn't start: " + startErr));
            errorLabel.setFontSize(12);
            errorLabel.setColor(Theme.ORANGE);
            errorLabel.setInsets(new Insets3f(0, 0, 12, 0));
        }

        boolean full = players.size() >= max;
        if (isHost && full) {
            Button start = panel.addChild(new Button("START TOURNAMENT"));
            styleButton(start, Theme.GREEN, Theme.ON_ACCENT, 17);
            start.setPreferredSize(new Vector3f(CARD_CONTENT_WIDTH, 48, 0));
            start.setInsets(new Insets3f(0, 0, 0, 0));
            start.addClickCommands(source -> {
                app.getAudioManager().playSfx("button_click.ogg");
                beginStart(app);
            });
        } else if (!full) {
            Label waiting = panel.addChild(new Label("Waiting for more players..."));
            waiting.setFontSize(13);
            waiting.setColor(Theme.TEXT_DIM);
        } else {
            Label waiting = panel.addChild(new Label("Full - waiting for the host to start..."));
            waiting.setFontSize(13);
            waiting.setColor(Theme.TEXT_DIM);
        }
    }

    private void beginStart(PaddleShockApp app) {
        if (startPending) {
            return;
        }
        startPending = true;
        startError.set(null);
        String hostPlayerId = app.getProfile().getPlayerId();
        String tournamentCode = code;
        Thread thread = new Thread(() -> {
            try {
                TournamentClient.State result = TournamentClient.startTournament(tournamentCode, hostPlayerId);
                pollResult.set(result);
            } catch (IOException e) {
                startError.set(e.getMessage() == null ? "the tournament service is unreachable" : e.getMessage());
            }
            startPending = false;
        }, "tournament-start");
        thread.setDaemon(true);
        thread.start();
    }

    // ============================== bracket ==============================

    private void buildBracket(PaddleShockApp app, Container panel) {
        String myId = app.getProfile().getPlayerId();

        Label title = panel.addChild(new Label("BRACKET"));
        title.setFontSize(26);
        title.setColor(Theme.BLUE);
        title.setInsets(new Insets3f(0, 0, 4, 0));

        Label codeLabel = panel.addChild(new Label("Code: " + code));
        codeLabel.setFontSize(13);
        codeLabel.setColor(Theme.TEXT_DIM);
        codeLabel.setInsets(new Insets3f(0, 0, 14, 0));

        if (state != null && state.isComplete()) {
            String champion = state.getChampion();
            boolean iWon = champion != null && champion.equals(myId);
            Container banner = panel.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
            banner.setBackground(new QuadBackgroundComponent(iWon ? Theme.GREEN_DIM : Theme.ORANGE_DIM));
            banner.setInsets(new Insets3f(10, 14, 10, 14));
            Label championLabel = banner.addChild(new Label(iWon ? "YOU ARE THE CHAMPION!" : "CHAMPION: " + displayNameFor(champion)));
            championLabel.setFontSize(18);
            championLabel.setColor(iWon ? Theme.GREEN : Theme.ORANGE);
            banner.setInsets(new Insets3f(0, 0, 16, 0));
        }

        TournamentClient.Bracket bracket = state == null ? null : state.getBracket();
        List<List<TournamentClient.Match>> rounds = bracket == null ? List.of() : bracket.getRounds();

        Container roundsRow = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        roundsRow.setInsets(new Insets3f(0, 0, 16, 0));

        int[] activeMatch = findActivePairing(rounds, myId); // {roundIndex, matchIndex} or null-marker (-1,-1)

        // Total round count for a single-elimination bracket is fixed by maxPlayers
        // (log2) from the start, even though the backend only appears to populate
        // `rounds` with entries for rounds generated so far (observed live: a fresh
        // 4-player bracket comes back with exactly one round of 2 matches, not a
        // pre-filled semifinal+final pair) - so round NAMES (FINAL/SEMIFINALS/...) are
        // computed from maxPlayers, not from rounds.size(), to avoid mislabeling an
        // early round as the final.
        int totalRounds = totalRoundsFor(state == null ? 0 : state.getMaxPlayers());

        for (int r = 0; r < rounds.size(); r++) {
            Container roundCol = roundsRow.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
            roundCol.setInsets(new Insets3f(0, 10, 0, 10));
            Label roundLabel = roundCol.addChild(new Label(roundName(r, totalRounds)));
            roundLabel.setFontSize(12);
            roundLabel.setColor(Theme.TEXT_DIM);
            roundLabel.setInsets(new Insets3f(0, 0, 6, 0));

            List<TournamentClient.Match> matches = rounds.get(r);
            for (int m = 0; m < matches.size(); m++) {
                boolean isMine = activeMatch[0] == r && activeMatch[1] == m;
                roundCol.addChild(buildMatchCard(app, matches.get(m), r, m, myId, isMine));
            }
        }
        if (rounds.isEmpty()) {
            Label empty = panel.addChild(new Label("Bracket not generated yet."));
            empty.setFontSize(13);
            empty.setColor(Theme.TEXT_DIM);
            empty.setInsets(new Insets3f(0, 0, 16, 0));
        }
    }

    /** log2(maxPlayers) - 4 players is 2 rounds (semifinal, final), 8 is 3 (quarter/semi/final).
     *  Falls back to 1 if maxPlayers is unknown/unexpected, so {@link #roundName} degrades to
     *  just always saying "FINAL" rather than dividing by zero or looping forever. */
    private int totalRoundsFor(int maxPlayers) {
        int rounds = 0;
        int n = Math.max(1, maxPlayers);
        while (n > 1) {
            n /= 2;
            rounds++;
        }
        return Math.max(1, rounds);
    }

    private String roundName(int index, int total) {
        int remaining = total - index;
        return switch (remaining) {
            case 1 -> "FINAL";
            case 2 -> "SEMIFINALS";
            case 3 -> "QUARTERFINALS";
            default -> "ROUND " + (index + 1);
        };
    }

    /** Finds the one pairing (if any) where {@code myId} is a filled slot with no winner yet -
     *  that's this player's own current/upcoming match, highlighted distinctly in the bracket.
     *  Returns {@code {-1, -1}} if none (eliminated, not yet placed into a future round, or the
     *  tournament is complete). */
    private int[] findActivePairing(List<List<TournamentClient.Match>> rounds, String myId) {
        for (int r = 0; r < rounds.size(); r++) {
            List<TournamentClient.Match> matches = rounds.get(r);
            for (int m = 0; m < matches.size(); m++) {
                TournamentClient.Match match = matches.get(m);
                boolean involved = myId.equals(match.getP1()) || myId.equals(match.getP2());
                if (involved && !match.isDecided()) {
                    return new int[] { r, m };
                }
            }
        }
        return new int[] { -1, -1 };
    }

    private Container buildMatchCard(PaddleShockApp app, TournamentClient.Match match, int roundIndex,
            int matchIndex, String myId, boolean isMine) {
        Container card = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        card.setBackground(new QuadBackgroundComponent(isMine ? Theme.BLUE_DIM : Theme.BACKGROUND_2));
        card.setInsets(new Insets3f(8, 10, 8, 10));

        addPlayerRow(card, match.getP1(), match.getWinner());
        addPlayerRow(card, match.getP2(), match.getWinner());

        if (isMine) {
            String p1 = match.getP1();
            String p2 = match.getP2();
            boolean iAmP1 = myId.equals(p1);
            boolean iAmP2 = myId.equals(p2);
            String lobbyCode = match.getLobbyCode();
            boolean isHostingThis = hostingRoundIndex == roundIndex && hostingMatchIndex == matchIndex;
            boolean isJoiningThis = joiningRoundIndex == roundIndex && joiningMatchIndex == matchIndex;

            if (p1 == null || p2 == null) {
                Label waiting = card.addChild(new Label("waiting for opponent..."));
                waiting.setFontSize(11);
                waiting.setColor(Theme.TEXT_DIM);
                waiting.setInsets(new Insets3f(6, 0, 0, 0));
            } else if (iAmP1 && lobbyCode == null) {
                if (isHostingThis && (matchHostPending || matchLobbyCode.get() != null)) {
                    String hosted = matchLobbyCode.get();
                    String text = matchHostError.get() != null ? "Error: " + matchHostError.get()
                            : hosted != null ? "Code " + hosted + " - waiting for opponent..."
                            : "Setting up match...";
                    Label statusLabel = card.addChild(new Label(text));
                    statusLabel.setFontSize(10);
                    statusLabel.setColor(matchHostError.get() != null ? Theme.ORANGE : Theme.TEXT_DIM);
                    statusLabel.setInsets(new Insets3f(6, 0, 0, 0));
                } else {
                    Button hostBtn = card.addChild(new Button("HOST THIS MATCH"));
                    styleSmallButton(hostBtn, Theme.ORANGE, Theme.ON_ACCENT);
                    hostBtn.setInsets(new Insets3f(6, 0, 0, 0));
                    hostBtn.addClickCommands(source -> {
                        app.getAudioManager().playSfx("button_click.ogg");
                        beginHostingBracketMatch(app, roundIndex, matchIndex);
                    });
                }
            } else if (iAmP1 && lobbyCode != null) {
                // The server has already confirmed our published code (this may be a poll result
                // landing after beginHostingBracketMatch's own local state was already shown, or a
                // fresh screen visit that finds a match already being hosted) - show the same
                // "waiting for opponent" message either way rather than falling through to the
                // generic "waiting..." below.
                Label statusLabel = card.addChild(new Label("Code " + lobbyCode + " - waiting for opponent..."));
                statusLabel.setFontSize(10);
                statusLabel.setColor(Theme.TEXT_DIM);
                statusLabel.setInsets(new Insets3f(6, 0, 0, 0));
            } else if (iAmP2 && lobbyCode != null && !isJoiningThis) {
                Button joinBtn = card.addChild(new Button("JOIN MATCH"));
                styleSmallButton(joinBtn, Theme.BLUE, Theme.ON_ACCENT);
                joinBtn.setInsets(new Insets3f(6, 0, 0, 0));
                joinBtn.addClickCommands(source -> {
                    app.getAudioManager().playSfx("button_click.ogg");
                    beginJoiningBracketMatch(app, roundIndex, matchIndex, lobbyCode);
                });
            } else if (isJoiningThis) {
                Label statusLabel = card.addChild(new Label(matchJoinError != null ? matchJoinError : "Connecting..."));
                statusLabel.setFontSize(10);
                statusLabel.setColor(matchJoinError != null ? Theme.ORANGE : Theme.TEXT_DIM);
                statusLabel.setInsets(new Insets3f(6, 0, 0, 0));
            } else {
                Label waiting = card.addChild(new Label(iAmP2 ? "waiting for host to start match..." : "waiting..."));
                waiting.setFontSize(11);
                waiting.setColor(Theme.TEXT_DIM);
                waiting.setInsets(new Insets3f(6, 0, 0, 0));
            }
        }
        // Fix the width only, after every child has already been added - the natural (content-
        // driven) height must stay whatever SpringGridLayout computed from those children, since
        // setting a Y that predates them (as an earlier version of this code did) locks the
        // container to a too-small height and drives the layout's later re-passes negative
        // (a real crash observed during live verification - see the fix note in git history).
        Vector3f natural = card.getPreferredSize();
        card.setPreferredSize(new Vector3f(170, natural.y, natural.z));
        return card;
    }

    private void addPlayerRow(Container card, String playerId, String winner) {
        String label = playerId == null ? "TBD" : displayNameFor(playerId);
        boolean won = playerId != null && playerId.equals(winner);
        boolean lost = winner != null && playerId != null && !playerId.equals(winner);
        Label row = card.addChild(new Label((won ? "* " : "") + label));
        row.setFontSize(12);
        row.setColor(won ? Theme.GREEN : lost ? Theme.TEXT_DIM2 : Theme.TEXT);
    }

    private String displayNameFor(String playerId) {
        if (state != null) {
            for (TournamentClient.PlayerEntry entry : state.getPlayers()) {
                if (entry.getPlayerId().equals(playerId)) {
                    return entry.displayName();
                }
            }
        }
        return shortId(playerId);
    }

    private void styleSmallButton(Button button, ColorRGBA bg, ColorRGBA fg) {
        button.setBackground(new QuadBackgroundComponent(bg));
        button.setColor(fg);
        button.setFontSize(11);
        button.setPreferredSize(new Vector3f(150, 30, 0));
        button.setInsets(new Insets3f(0, 0, 0, 0));
    }

    // ---- hosting/joining one bracket pairing ----

    private void beginHostingBracketMatch(PaddleShockApp app, int roundIndex, int matchIndex) {
        if (matchNetHost != null) {
            matchNetHost.close();
        }
        try {
            matchNetHost = app.startHostMatch(GameConstants.MULTIPLAYER_DEFAULT_PORT, false);
        } catch (SocketException e) {
            try {
                matchNetHost = app.startHostMatch(0, false);
            } catch (SocketException e2) {
                matchHostError.set("Could not open a UDP port: " + e2.getMessage());
                rebuild();
                return;
            }
        }
        hostingRoundIndex = roundIndex;
        hostingMatchIndex = matchIndex;
        matchLobbyCode.set(null);
        matchHostError.set(null);
        matchHostPending = true;
        matchHostResultShown = false;
        rebuild();

        NetHost hostRef = matchNetHost;
        if (hostRef.getPublicAddress() == null) {
            matchHostPending = false;
            matchHostError.set("no public address available for internet play");
            rebuild();
            return;
        }
        if (hostRef.isSymmetricNatSuspected()) {
            matchHostPending = false;
            matchHostError.set(StunClient.SYMMETRIC_NAT_MESSAGE);
            rebuild();
            return;
        }

        String tournamentCode = code;
        String selfId = app.getProfile().getPlayerId();
        Thread thread = new Thread(() -> {
            String lobbyCode = null;
            String error = null;
            try {
                lobbyCode = hostRef.registerLobby();
            } catch (IOException e) {
                error = e.getMessage();
            }
            matchLobbyCode.set(lobbyCode);
            matchHostError.set(error);
            matchHostPending = false;
            if (lobbyCode != null) {
                try {
                    TournamentClient.setTournamentMatchLobbyCode(tournamentCode, roundIndex, matchIndex, selfId, lobbyCode);
                } catch (IOException e) {
                    matchHostError.set("Could not publish lobby code: " + e.getMessage());
                }
                hostRef.pollAndPunchUntilJoined(lobbyCode);
            }
        }, "tournament-match-host");
        thread.setDaemon(true);
        thread.start();
    }

    private void beginJoiningBracketMatch(PaddleShockApp app, int roundIndex, int matchIndex, String lobbyCode) {
        if (matchNetClient != null) {
            matchNetClient.close();
            matchNetClient = null;
        }
        joiningRoundIndex = roundIndex;
        joiningMatchIndex = matchIndex;
        matchJoinError = null;
        matchJoinTimeoutTimer = 0f;
        matchHelloRetryTimer = 0f;
        try {
            matchNetClient = app.joinMatchByLobbyCode(lobbyCode);
        } catch (IOException e) {
            matchJoinError = "Could not connect: " + e.getMessage();
        }
        rebuild();
    }

    private void copyToClipboard(String text) {
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(text), null);
        } catch (Exception e) {
            // best-effort convenience only - the code is still shown on screen
        }
    }

    // ============================== shared shell / polling ==============================

    private void rebuild() {
        uiRoot.detachAllChildren();
        PaddleShockApp app = (PaddleShockApp) getApplication();
        SimpleApplication simpleApp = (SimpleApplication) app;
        float screenW = simpleApp.getCamera().getWidth();
        float screenH = simpleApp.getCamera().getHeight();

        Container background = new Container();
        background.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND));
        background.setPreferredSize(new Vector3f(screenW, screenH, 0));
        background.setLocalTranslation(0, screenH, 0);
        uiRoot.attachChild(background);

        Container panel = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        panel.setBackground(new QuadBackgroundComponent(Theme.PANEL));
        panel.setInsets(new Insets3f(24, 32, 24, 32));

        switch (view) {
            case CREATE_JOIN -> buildCreateJoin(app, panel);
            case WAITING -> buildWaiting(app, panel);
            case BRACKET -> buildBracket(app, panel);
        }

        Container card = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        card.setBackground(new QuadBackgroundComponent(Theme.PANEL_LINE));
        card.setInsets(new Insets3f(2, 2, 2, 2));
        card.addChild(panel);

        Vector3f cardSize = card.getPreferredSize();
        float cardTopY = (screenH + cardSize.y) / 2f;
        card.setLocalTranslation((screenW - cardSize.x) / 2f, cardTopY, 1);
        uiRoot.attachChild(card);

        Button back = new Button("BACK");
        styleButton(back, Theme.PANEL_HOVER, Theme.TEXT, 14);
        back.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            app.showMultiplayer();
        });
        Vector3f backSize = back.getPreferredSize();
        float cardBottomY = cardTopY - cardSize.y;
        back.setLocalTranslation((screenW - backSize.x) / 2f, cardBottomY - 18, 1);
        uiRoot.attachChild(back);
    }

    private void styleButton(Button button, ColorRGBA bg, ColorRGBA fg, int fontSize) {
        button.setInsets(new Insets3f(6, 0, 6, 0));
        button.setBackground(new QuadBackgroundComponent(bg));
        button.setColor(fg);
        button.setFontSize(fontSize);
        button.setPreferredSize(new Vector3f(340, 46, 0));
    }

    private void beginPoll() {
        if (code == null) {
            return;
        }
        pollPending = true;
        int myGeneration = pollGeneration.incrementAndGet();
        String tournamentCode = code;
        Thread thread = new Thread(() -> {
            TournamentClient.State result = null;
            String error = null;
            try {
                result = TournamentClient.getTournamentState(tournamentCode);
            } catch (IOException e) {
                error = e.getMessage() == null ? "the tournament service is unreachable" : e.getMessage();
            }
            if (pollGeneration.get() != myGeneration) {
                return; // superseded - discard
            }
            if (result != null) {
                pollResult.set(result);
            }
            pollError.set(error);
            pollPending = false;
        }, "tournament-poll");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void update(float tpf) {
        PaddleShockApp app = (PaddleShockApp) getApplication();

        // Pick up the create/join action result once it lands.
        if (view == View.CREATE_JOIN && !actionPending) {
            TournamentClient.State result = actionResult.getAndSet(null);
            if (result != null) {
                code = result.getCode();
                state = result;
                view = View.WAITING;
                rebuild();
            } else {
                String error = actionError.getAndSet(null);
                if (error != null) {
                    createJoinError = error;
                    rebuild();
                }
            }
        }

        // Pick up any fresh poll/start result and advance the view as the tournament progresses.
        TournamentClient.State fresh = pollResult.getAndSet(null);
        if (fresh != null) {
            state = fresh;
            View nextView = (state.isInProgress() || state.isComplete()) ? View.BRACKET : View.WAITING;
            if (nextView != view) {
                view = nextView;
            }
            rebuild();
        } else {
            String error = pollError.get();
            if (error != null && !error.equals(lastPollError) && (view == View.WAITING || view == View.BRACKET)) {
                lastPollError = error;
                rebuild();
            }
        }

        // Lightweight periodic re-poll while in the waiting room or bracket view.
        if ((view == View.WAITING || view == View.BRACKET) && code != null && !pollPending) {
            pollTimer += tpf;
            if (pollTimer >= POLL_INTERVAL_SECONDS) {
                pollTimer = 0f;
                beginPoll();
            }
        }

        // Rebuild once the host-a-match background work produces a visible change (the lobby
        // code appearing, or an error) - same one-shot-reveal pattern as MultiplayerState's
        // lobbyResultShown.
        if (view == View.BRACKET && hostingRoundIndex >= 0 && !matchHostResultShown && !matchHostPending
                && (matchLobbyCode.get() != null || matchHostError.get() != null)) {
            matchHostResultShown = true;
            rebuild();
        }

        // Hosting a bracket match: hand off once a joiner connects.
        if (matchNetHost != null && matchNetHost.hasJoiner()) {
            NetHost handoff = matchNetHost;
            matchNetHost = null;
            String opponentId = handoff.getJoinerPlayerId();
            app.setActiveTournamentContext(new PaddleShockApp.TournamentMatchContext(
                    code, hostingRoundIndex, hostingMatchIndex, app.getProfile().getPlayerId(), opponentId));
            app.enterHostedMatch(handoff);
            return;
        }

        // Joining a bracket match: same connect/timeout/retry handling as MultiplayerState's
        // JOINING view.
        if (matchNetClient != null) {
            if (matchNetClient.isConnected()) {
                NetClient handoff = matchNetClient;
                matchNetClient = null;
                String opponentId = opponentIdFor(joiningRoundIndex, joiningMatchIndex, app.getProfile().getPlayerId());
                app.setActiveTournamentContext(new PaddleShockApp.TournamentMatchContext(
                        code, joiningRoundIndex, joiningMatchIndex, app.getProfile().getPlayerId(), opponentId));
                app.enterJoinedMatch(handoff);
                return;
            } else if (matchNetClient.isRejected()) {
                matchJoinError = "Host already has an opponent connected.";
                matchNetClient.close();
                matchNetClient = null;
                rebuild();
            } else {
                matchJoinTimeoutTimer += tpf;
                if (matchJoinTimeoutTimer >= JOIN_TIMEOUT_SECONDS) {
                    matchJoinError = "Could not connect to the match host.";
                    matchNetClient.close();
                    matchNetClient = null;
                    rebuild();
                } else {
                    matchHelloRetryTimer += tpf;
                    if (matchHelloRetryTimer >= HELLO_RETRY_INTERVAL_SECONDS) {
                        matchHelloRetryTimer = 0f;
                        matchNetClient.sendHello();
                    }
                }
            }
        }
    }

    private String opponentIdFor(int roundIndex, int matchIndex, String myId) {
        if (state == null || state.getBracket() == null) {
            return null;
        }
        List<List<TournamentClient.Match>> rounds = state.getBracket().getRounds();
        if (roundIndex < 0 || roundIndex >= rounds.size()) {
            return null;
        }
        List<TournamentClient.Match> matches = rounds.get(roundIndex);
        if (matchIndex < 0 || matchIndex >= matches.size()) {
            return null;
        }
        TournamentClient.Match match = matches.get(matchIndex);
        return myId.equals(match.getP1()) ? match.getP2() : match.getP1();
    }

    @Override
    protected void cleanup(Application application) {
        // uiRoot is detached in onDisable(); net resources are handed off to GameplayAppState on
        // success, or closed explicitly on BACK - nothing else owns native resources here.
    }

    @Override
    protected void onEnable() {
        view = View.CREATE_JOIN;
        code = null;
        state = null;
        createJoinError = null;
        actionPending = false;
        pollTimer = 0f;
        lastPollError = null;
        startError.set(null);
        hostingRoundIndex = -1;
        hostingMatchIndex = -1;
        matchLobbyCode.set(null);
        matchHostError.set(null);
        matchHostPending = false;
        matchHostResultShown = false;
        joiningRoundIndex = -1;
        joiningMatchIndex = -1;
        matchJoinError = null;
        rebuild();
        ((SimpleApplication) getApplication()).getGuiNode().attachChild(uiRoot);
        getApplication().getInputManager().setCursorVisible(true);
    }

    @Override
    protected void onDisable() {
        uiRoot.removeFromParent();
        actionGeneration.incrementAndGet();
        pollGeneration.incrementAndGet();
        if (matchNetHost != null) {
            matchNetHost.close();
            matchNetHost = null;
        }
        if (matchNetClient != null) {
            matchNetClient.close();
            matchNetClient = null;
        }
    }
}
