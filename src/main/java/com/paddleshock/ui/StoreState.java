package com.paddleshock.ui;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Command;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.Insets3f;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.component.SpringGridLayout;

import com.paddleshock.app.PlayerContext;
import com.paddleshock.i18n.I18n;
import com.paddleshock.data.BallDefinition;
import com.paddleshock.data.Catalog;
import com.paddleshock.data.PaddleDefinition;
import com.paddleshock.data.PlayerProfile;
import com.paddleshock.data.PowerUpDefinition;
import com.paddleshock.data.TableDefinition;

/** The store: category tabs, a grid of item cards, currency, buy/equip. */
public class StoreState extends BaseAppState {

    private static final float HEADER_HEIGHT = 92f;
    private static final float FOOTER_HEIGHT = 64f;
    private static final float FULL_CARD_WIDTH = 340f;
    private static final float FULL_CARD_HEIGHT = 318f;
    private static final float FULL_SWATCH_HEIGHT = 60f;
    private static final float MODAL_CARD_WIDTH = 230f;
    private static final float MODAL_CARD_HEIGHT = 276f;
    private static final float MODAL_SWATCH_HEIGHT = 50f;

    /** Rounded-up average of a match's credit reward range - the "typical" win used to turn a
     *  price gap into a rough "X more wins to unlock" estimate. Flavor text, not a prediction. */
    private static final int AVG_MATCH_REWARD = (com.paddleshock.GameConstants.MATCH_REWARD_MIN
            + com.paddleshock.GameConstants.MATCH_REWARD_MAX + 1) / 2;

    private final Node uiRoot = new Node("storeUi");
    private String selectedCategory = "paddle";

    /** When true, renders as a dimmed overlay panel (opened from match setup) instead of a full screen. */
    private boolean modal = false;
    private Runnable backAction = () -> {
    };
    private float cardWidth = FULL_CARD_WIDTH;
    private float cardHeight = FULL_CARD_HEIGHT;
    private float swatchHeight = FULL_SWATCH_HEIGHT;

    /** Opens as a full-screen browse (main menu / match-end "STORE" buttons); returns to onClose. */
    public void showFull(Runnable onClose) {
        this.modal = false;
        this.backAction = onClose;
    }

    /** Opens as a dimmed modal on top of whatever's currently shown (match setup); returns via onClose. */
    public void showAsModal(Runnable onClose) {
        this.modal = true;
        this.backAction = onClose;
    }

    @Override
    protected void initialize(Application application) {
        // Built fresh in rebuild() every time the screen is shown or a category/purchase changes.
    }

    private void rebuild(PlayerContext app) {
        uiRoot.detachAllChildren();

        SimpleApplication simpleApp = (SimpleApplication) getApplication();
        float screenW = simpleApp.getCamera().getWidth();
        float screenH = simpleApp.getCamera().getHeight();

        cardWidth = modal ? MODAL_CARD_WIDTH : FULL_CARD_WIDTH;
        cardHeight = modal ? MODAL_CARD_HEIGHT : FULL_CARD_HEIGHT;
        swatchHeight = modal ? MODAL_SWATCH_HEIGHT : FULL_SWATCH_HEIGHT;

        // A category with more items than the 3 every other tab has (currently just POWER-UPS,
        // at 4) would otherwise overflow past the screen edge at the fixed card width - shrink
        // proportionally to whatever actually fits instead of letting cards run off-screen.
        int itemCount = itemCountFor(selectedCategory);
        float maxRowWidth = screenW - (modal ? 80f : 112f);
        if (itemCount > 0 && itemCount * cardWidth > maxRowWidth) {
            cardWidth = maxRowWidth / itemCount;
        }

        if (modal) {
            buildModal(app, screenW, screenH);
        } else {
            buildBackground(screenW, screenH);
            buildHeader(app, screenW, screenH);
            buildCards(app, screenW, screenH);
            buildFooter(app, screenW);
        }
    }

    /** A dimmed backdrop plus a single centered panel holding tabs, cards and a close button. */
    private void buildModal(PlayerContext app, float screenW, float screenH) {
        Container overlay = new Container();
        com.simsilica.lemur.component.QuadBackgroundComponent overlayBg = quad(Theme.BACKGROUND);
        overlayBg.setAlpha(0.82f);
        overlay.setBackground(overlayBg);
        overlay.setPreferredSize(new Vector3f(screenW, screenH, 0));
        overlay.setLocalTranslation(0, screenH, 0);
        uiRoot.attachChild(overlay);

        Container panel = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        panel.setBackground(quad(Theme.PANEL));
        panel.setInsets(new Insets3f(20, 26, 20, 26));

        Label title = panel.addChild(new Label(I18n.t("store.title")));
        title.setFontSize(22);
        title.setColor(Theme.ORANGE);
        title.setInsets(new Insets3f(0, 0, 2, 0));

        PlayerProfile profile = app.getProfile();
        Container creditsPill = panel.addChild(buildCreditsPill(profile));
        creditsPill.setInsets(new Insets3f(0, 0, 12, 0));

        Container tabs = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        tabs.setInsets(new Insets3f(0, 0, 12, 0));
        addTab(tabs, app, I18n.t("store.tab_paddles"), "paddle");
        addTab(tabs, app, I18n.t("store.tab_tables"), "table");
        addTab(tabs, app, I18n.t("store.tab_balls"), "ball");
        addTab(tabs, app, I18n.t("store.tab_powerups"), "powerup");

        Container cardsRow = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        cardsRow.setInsets(new Insets3f(0, 0, 16, 0));
        populateCards(cardsRow, app, profile);

        Button close = panel.addChild(new Button(I18n.t("store.close")));
        close.setBackground(quad(Theme.ORANGE));
        close.setColor(Theme.ON_ACCENT);
        close.setFontSize(15);
        close.setPreferredSize(new Vector3f(220, 42, 0));
        close.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            setEnabled(false);
            backAction.run();
        });

        Vector3f panelSize = panel.getPreferredSize();
        panel.setLocalTranslation((screenW - panelSize.x) / 2f, (screenH + panelSize.y) / 2f, 1);
        uiRoot.attachChild(panel);
    }

    private void buildBackground(float screenW, float screenH) {
        Container background = new Container();
        background.setBackground(quad(Theme.BACKGROUND));
        background.setPreferredSize(new Vector3f(screenW, screenH, 0));
        background.setLocalTranslation(0, screenH, 0);
        uiRoot.attachChild(background);
    }

    private void buildHeader(PlayerContext app, float screenW, float screenH) {
        Container headerBar = new Container();
        headerBar.setBackground(quad(Theme.PANEL));
        headerBar.setPreferredSize(new Vector3f(screenW, HEADER_HEIGHT, 0));
        headerBar.setLocalTranslation(0, screenH, 1);
        uiRoot.attachChild(headerBar);

        Button back = new Button(I18n.t("store.back"));
        back.setBackground(quad(Theme.PANEL_HOVER));
        back.setColor(Theme.TEXT);
        back.setFontSize(15);
        back.setInsets(new Insets3f(8, 10, 8, 10));
        Vector3f backSize = back.getPreferredSize();
        back.setLocalTranslation(28, screenH - (HEADER_HEIGHT - backSize.y) / 2f, 2);
        back.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            setEnabled(false);
            backAction.run();
        });
        uiRoot.attachChild(back);

        Label title = new Label(I18n.t("store.title"));
        title.setFontSize(26);
        title.setColor(Theme.ORANGE);
        Vector3f titleSize = title.getPreferredSize();
        title.setLocalTranslation(28 + backSize.x + 20, screenH - (HEADER_HEIGHT - titleSize.y) / 2f, 2);
        uiRoot.attachChild(title);

        Container tabs = new Container(new SpringGridLayout(Axis.X, Axis.Y));
        addTab(tabs, app, I18n.t("store.tab_paddles"), "paddle");
        addTab(tabs, app, I18n.t("store.tab_tables"), "table");
        addTab(tabs, app, I18n.t("store.tab_balls"), "ball");
        addTab(tabs, app, I18n.t("store.tab_powerups"), "powerup");
        Vector3f tabsSize = tabs.getPreferredSize();
        tabs.setLocalTranslation((screenW - tabsSize.x) / 2f, screenH - (HEADER_HEIGHT - tabsSize.y) / 2f, 2);
        uiRoot.attachChild(tabs);

        PlayerProfile profile = app.getProfile();
        Container creditsPill = buildCreditsPill(profile);
        Vector3f pillSize = creditsPill.getPreferredSize();
        creditsPill.setLocalTranslation(screenW - pillSize.x - 28, screenH - (HEADER_HEIGHT - pillSize.y) / 2f, 2);
        uiRoot.attachChild(creditsPill);
    }

    /** A pill-shaped credits badge: a small orange accent dot plus "N CREDITS" text, on a dim
     *  orange-tinted background. Used in both the store and match-setup headers for a consistent
     *  "currency" affordance. */
    private Container buildCreditsPill(PlayerProfile profile) {
        Container pill = new Container(new SpringGridLayout(Axis.X, Axis.Y));
        pill.setBackground(quad(Theme.ORANGE_DIM));
        pill.setInsets(new Insets3f(8, 14, 8, 14));

        Container dot = pill.addChild(new Container());
        dot.setBackground(quad(Theme.ORANGE));
        dot.setPreferredSize(new Vector3f(10, 10, 0));
        dot.setInsets(new Insets3f(2, 0, 2, 8));

        Label label = pill.addChild(new Label(I18n.t("store.credits", profile.getCurrency())));
        label.setColor(Theme.ORANGE);
        label.setFontSize(16);
        return pill;
    }

    /** Restyled as a pill-shaped toggle: active tab is a filled orange pill, inactive tabs are
     *  flat PANEL pills - same click behavior (switches {@link #selectedCategory}) as before. */
    private void addTab(Container tabs, PlayerContext app, String label, String category) {
        Button tab = tabs.addChild(new Button(label));
        boolean active = category.equals(selectedCategory);
        tab.setInsets(new Insets3f(8, 16, 8, 16));
        tab.setBackground(quad(active ? Theme.ORANGE : Theme.PANEL));
        tab.setColor(active ? Theme.ON_ACCENT : Theme.TEXT_DIM);
        tab.setFontSize(16);
        tab.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            selectedCategory = category;
            rebuild(app);
        });
    }

    private int itemCountFor(String category) {
        return switch (category) {
            case "paddle" -> Catalog.PADDLES.size();
            case "table" -> Catalog.TABLES.size();
            case "ball" -> Catalog.BALLS.size();
            case "powerup" -> Catalog.POWERUPS.size();
            default -> 0;
        };
    }

    private void buildCards(PlayerContext app, float screenW, float screenH) {
        Container cardsRow = new Container(new SpringGridLayout(Axis.X, Axis.Y));
        PlayerProfile profile = app.getProfile();
        populateCards(cardsRow, app, profile);

        Vector3f rowSize = cardsRow.getPreferredSize();
        cardsRow.setLocalTranslation((screenW - rowSize.x) / 2f,
                screenH - HEADER_HEIGHT - 40f, 1);
        uiRoot.attachChild(cardsRow);
    }

    private void populateCards(Container cardsRow, PlayerContext app, PlayerProfile profile) {
        switch (selectedCategory) {
            case "paddle" -> {
                for (PaddleDefinition item : Catalog.PADDLES) {
                    String[] stats = { I18n.t("store.stat_speed", percent(item.getSpeedMultiplier())),
                            I18n.t("store.stat_size", percent(item.getSizeMultiplier())) };
                    addCard(cardsRow, app, profile, "paddle", item.getId(), item.getDisplayName(),
                            item.getPrice(), item.getColor(), stats);
                }
            }
            case "table" -> {
                for (TableDefinition item : Catalog.TABLES) {
                    String[] stats = { I18n.t("store.stat_bounce", percent(item.getRestitutionMultiplier())) };
                    addCard(cardsRow, app, profile, "table", item.getId(), item.getDisplayName(),
                            item.getPrice(), item.getSurfaceColor(), stats);
                }
            }
            case "ball" -> {
                for (BallDefinition item : Catalog.BALLS) {
                    String[] stats = { I18n.t("store.stat_speed", percent(item.getSpeedMultiplier())),
                            I18n.t("store.stat_size", percent(item.getSizeMultiplier())) };
                    addCard(cardsRow, app, profile, "ball", item.getId(), item.getDisplayName(),
                            item.getPrice(), item.getColor(), stats);
                }
            }
            case "powerup" -> {
                for (PowerUpDefinition item : Catalog.POWERUPS) {
                    addPowerUpCard(cardsRow, app, profile, item);
                }
            }
            default -> throw new IllegalStateException("Unknown category: " + selectedCategory);
        }
    }

    private void addCard(Container cardsRow, PlayerContext app, PlayerProfile profile, String category,
            String id, String displayName, int price, ColorRGBA tint, String[] statTags) {

        boolean owned = profile.owns(category, id);
        boolean equipped = owned && profile.getEquippedId(category).equals(id);

        ColorRGBA cardColor = equipped ? mix(Theme.PANEL, Theme.GREEN, 0.12f)
                : owned ? mix(Theme.PANEL, Theme.BLUE, 0.10f)
                : Theme.PANEL;

        Container card = newBorderedCard(cardsRow);
        card.setBackground(quad(cardColor));
        card.setPreferredSize(new Vector3f(cardWidth, cardHeight, 0));

        Container swatch = card.addChild(new Container());
        swatch.setBackground(quad(tint));
        swatch.setPreferredSize(new Vector3f(cardWidth, swatchHeight, 0));

        Label name = card.addChild(new Label(displayName.toUpperCase()));
        name.setInsets(new Insets3f(10, 16, 4, 16));
        name.setFontSize(20);
        name.setColor(owned || equipped ? Theme.TEXT : Theme.TEXT_DIM);

        String statusText = equipped ? I18n.t("store.equipped") : owned ? I18n.t("store.owned") : I18n.t("store.price_credits", price);
        Label status = card.addChild(new Label(statusText));
        status.setInsets(new Insets3f(0, 16, 4, 16));
        status.setFontSize(14);
        status.setColor(Theme.TEXT_DIM);

        addStatTags(card, statTags);

        if (!owned) {
            String hint = winsHint(price, profile.getCurrency());
            if (hint != null) {
                Label winsLabel = card.addChild(new Label(hint));
                winsLabel.setInsets(new Insets3f(0, 16, 6, 16));
                winsLabel.setFontSize(11);
                winsLabel.setColor(Theme.TEXT_DIM);
            }
        }

        String actionLabel = equipped ? I18n.t("store.equipped") : owned ? I18n.t("store.equip") : I18n.t("store.buy_price", price);
        ColorRGBA actionBg = equipped ? Theme.GREEN_DIM : owned ? Theme.BLUE : Theme.ORANGE;
        ColorRGBA actionFg = equipped ? Theme.GREEN : Theme.ON_ACCENT;

        Button action = card.addChild(new Button(actionLabel));
        action.setInsets(new Insets3f(10, 16, 14, 16));
        action.setBackground(quad(actionBg));
        action.setColor(actionFg);
        action.setFontSize(15);
        action.setPreferredSize(new Vector3f(cardWidth - 32, 40, 0));
        action.setEnabled(!equipped);
        action.addClickCommands((Command<Button>) source -> {
            boolean success = true;
            if (owned) {
                profile.equip(category, id);
            } else {
                success = profile.purchase(category, id, price);
            }
            app.saveProfile();
            app.getAudioManager().playSfx(success ? "button_confirm.ogg" : "purchase_denied.ogg");
            rebuild(app);
        });
    }

    private void addPowerUpCard(Container cardsRow, PlayerContext app, PlayerProfile profile, PowerUpDefinition item) {
        boolean owned = profile.ownsPowerUp(item.getId());
        int assignedSlot = -1;
        for (int i = 0; i < 3; i++) {
            if (item.getId().equals(profile.getLoadoutSlot(i))) {
                assignedSlot = i;
            }
        }

        ColorRGBA cardColor = assignedSlot >= 0 ? mix(Theme.PANEL, Theme.GREEN, 0.12f)
                : owned ? mix(Theme.PANEL, Theme.BLUE, 0.10f)
                : Theme.PANEL;

        Container card = newBorderedCard(cardsRow);
        card.setBackground(quad(cardColor));
        card.setPreferredSize(new Vector3f(cardWidth, cardHeight, 0));

        Container swatch = card.addChild(new Container());
        swatch.setBackground(quad(item.getType().getColor()));
        swatch.setPreferredSize(new Vector3f(cardWidth, swatchHeight, 0));

        Label name = card.addChild(new Label(item.getDisplayName().toUpperCase()));
        name.setInsets(new Insets3f(10, 16, 4, 16));
        name.setFontSize(20);
        name.setColor(owned ? Theme.TEXT : Theme.TEXT_DIM);

        String statusText = assignedSlot >= 0 ? I18n.t("store.key_slot", assignedSlot + 1)
                : owned ? I18n.t("store.owned") : I18n.t("store.price_credits", item.getPrice());
        Label status = card.addChild(new Label(statusText));
        status.setInsets(new Insets3f(0, 16, 4, 16));
        status.setFontSize(14);
        status.setColor(Theme.TEXT_DIM);

        String[] statTags = {
            I18n.t("store.stat_cooldown", Math.round(item.getCooldownSeconds())),
            I18n.t("store.stat_duration", Math.round(item.getType().getDuration()))
        };
        addStatTags(card, statTags);

        if (!owned) {
            String hint = winsHint(item.getPrice(), profile.getCurrency());
            if (hint != null) {
                Label winsLabel = card.addChild(new Label(hint));
                winsLabel.setInsets(new Insets3f(0, 16, 6, 16));
                winsLabel.setFontSize(11);
                winsLabel.setColor(Theme.TEXT_DIM);
            }

            Button buy = card.addChild(new Button(I18n.t("store.buy_price", item.getPrice())));
            buy.setInsets(new Insets3f(10, 16, 14, 16));
            buy.setBackground(quad(Theme.ORANGE));
            buy.setColor(Theme.ON_ACCENT);
            buy.setFontSize(15);
            buy.setPreferredSize(new Vector3f(cardWidth - 32, 40, 0));
            buy.addClickCommands((Command<Button>) source -> {
                boolean success = profile.purchasePowerUp(item.getId(), item.getPrice());
                app.saveProfile();
                app.getAudioManager().playSfx(success ? "button_confirm.ogg" : "purchase_denied.ogg");
                rebuild(app);
            });
        } else {
            Container slots = card.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
            slots.setInsets(new Insets3f(10, 16, 14, 16));
            for (int i = 0; i < 3; i++) {
                boolean isThisSlot = i == assignedSlot;
                Button slotButton = slots.addChild(new Button(I18n.t("store.key_slot", i + 1)));
                slotButton.setBackground(quad(isThisSlot ? Theme.GREEN_DIM : Theme.PANEL_HOVER));
                slotButton.setColor(isThisSlot ? Theme.GREEN : Theme.TEXT);
                slotButton.setFontSize(13);
                slotButton.setPreferredSize(new Vector3f((cardWidth - 32) / 3f, 36, 0));
                int slotIndex = i;
                slotButton.addClickCommands((Command<Button>) source -> {
                    profile.setLoadoutSlot(slotIndex, isThisSlot ? "" : item.getId());
                    app.saveProfile();
                    app.getAudioManager().playSfx("button_click.ogg");
                    rebuild(app);
                });
            }
        }
    }

    /** Just a decorative bottom stripe now - BACK lives in the header (see {@link #buildHeader}). */
    private void buildFooter(PlayerContext app, float screenW) {
        Container footerBar = new Container();
        footerBar.setBackground(quad(Theme.PANEL));
        footerBar.setPreferredSize(new Vector3f(screenW, FOOTER_HEIGHT, 0));
        footerBar.setLocalTranslation(0, FOOTER_HEIGHT, 1);
        uiRoot.attachChild(footerBar);
    }

    /**
     * How many more average-reward wins it'd take to afford an item costing {@code price} at
     * {@code currentCredits}, rounded up. 0 if it's already affordable. Package-visible (rather
     * than private) so it's directly unit-testable without touching any Lemur/jME UI code.
     */
    static int estimateWinsNeeded(int price, int currentCredits) {
        int shortfall = price - currentCredits;
        if (shortfall <= 0) {
            return 0;
        }
        return (shortfall + AVG_MATCH_REWARD - 1) / AVG_MATCH_REWARD;
    }

    /** "~4 more wins to unlock" hint text, or {@code null} when the item's already affordable
     *  (no framing needed - see {@link #estimateWinsNeeded}). */
    private static String winsHint(int price, int currentCredits) {
        int wins = estimateWinsNeeded(price, currentCredits);
        if (wins <= 0) {
            return null;
        }
        return wins == 1 ? I18n.t("store.wins_hint_one", wins) : I18n.t("store.wins_hint_many", wins);
    }

    private static String percent(float multiplier) {
        return Math.round(multiplier * 100) + "%";
    }

    private static ColorRGBA mix(ColorRGBA base, ColorRGBA tint, float amount) {
        return new ColorRGBA(
                base.r + (tint.r - base.r) * amount,
                base.g + (tint.g - base.g) * amount,
                base.b + (tint.b - base.b) * amount,
                1f);
    }

    private static com.simsilica.lemur.component.QuadBackgroundComponent quad(ColorRGBA color) {
        return new com.simsilica.lemur.component.QuadBackgroundComponent(color);
    }

    /**
     * Adds a new card container to {@code cardsRow}, wrapped in a thin {@code Theme.PANEL_LINE}
     * border. The border is faked with two nested containers: an outer one painted with the
     * border color whose only child (the actual card) has a small inset, so the border color
     * peeks out around the card's edge - a common borderless-toolkit trick, avoids needing a
     * texture asset for a real 9-patch border. Returns the inner card container to populate.
     */
    private Container newBorderedCard(Container cardsRow) {
        Container border = cardsRow.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        border.setBackground(quad(Theme.PANEL_LINE));
        border.setInsets(new Insets3f(0, 12, 0, 12));

        Container card = new Container(new SpringGridLayout(Axis.Y, Axis.X));
        card.setInsets(new Insets3f(2, 2, 2, 2));
        border.addChild(card);
        return card;
    }

    /** Renders each stat as a small pill-shaped tag in a row (e.g. "SPEED 110%"), instead of one
     *  plain stacked line of text. */
    private void addStatTags(Container card, String[] statTags) {
        Container tagsRow = card.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        tagsRow.setInsets(new Insets3f(4, 16, 14, 16));
        for (String tag : statTags) {
            Label tagLabel = tagsRow.addChild(new Label(tag));
            tagLabel.setInsets(new Insets3f(3, 8, 3, 8));
            tagLabel.setBackground(quad(Theme.BACKGROUND_2));
            tagLabel.setColor(Theme.TEXT_DIM);
            tagLabel.setFontSize(11);
        }
    }

    @Override
    protected void cleanup(Application application) {
        // uiRoot is detached in onDisable(); nothing else owns native resources here.
    }

    @Override
    protected void onEnable() {
        rebuild((PlayerContext) getApplication());
        // Pushed well above the Z range any other screen uses, so a modal open on top of
        // match setup always wins the GUI bucket's back-to-front draw (and pick) order.
        uiRoot.setLocalTranslation(0, 0, modal ? 50f : 0f);
        ((SimpleApplication) getApplication()).getGuiNode().attachChild(uiRoot);
        getApplication().getInputManager().setCursorVisible(true);
    }

    @Override
    protected void onDisable() {
        uiRoot.removeFromParent();
    }
}
