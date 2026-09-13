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

import com.paddleshock.app.PaddleShockApp;
import com.paddleshock.data.BallDefinition;
import com.paddleshock.data.Catalog;
import com.paddleshock.data.LevelDefinition;
import com.paddleshock.data.PaddleDefinition;
import com.paddleshock.data.PlayerProfile;
import com.paddleshock.data.PowerUpDefinition;
import com.paddleshock.data.TableDefinition;

/** The store: category tabs, a grid of item cards, currency, buy/equip. */
public class StoreState extends BaseAppState {

    private static final float HEADER_HEIGHT = 92f;
    private static final float FOOTER_HEIGHT = 64f;
    private static final float FULL_CARD_WIDTH = 340f;
    private static final float FULL_CARD_HEIGHT = 300f;
    private static final float FULL_SWATCH_HEIGHT = 60f;
    private static final float MODAL_CARD_WIDTH = 230f;
    private static final float MODAL_CARD_HEIGHT = 260f;
    private static final float MODAL_SWATCH_HEIGHT = 50f;

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

    private void rebuild(PaddleShockApp app) {
        uiRoot.detachAllChildren();

        SimpleApplication simpleApp = (SimpleApplication) app;
        float screenW = simpleApp.getCamera().getWidth();
        float screenH = simpleApp.getCamera().getHeight();

        cardWidth = modal ? MODAL_CARD_WIDTH : FULL_CARD_WIDTH;
        cardHeight = modal ? MODAL_CARD_HEIGHT : FULL_CARD_HEIGHT;
        swatchHeight = modal ? MODAL_SWATCH_HEIGHT : FULL_SWATCH_HEIGHT;

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
    private void buildModal(PaddleShockApp app, float screenW, float screenH) {
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

        Label title = panel.addChild(new Label("STORE"));
        title.setFontSize(22);
        title.setColor(Theme.ORANGE);
        title.setInsets(new Insets3f(0, 0, 2, 0));

        PlayerProfile profile = app.getProfile();
        Label currency = panel.addChild(new Label(profile.getCurrency() + " credits"));
        currency.setFontSize(14);
        currency.setColor(Theme.TEXT_DIM);
        currency.setInsets(new Insets3f(0, 0, 12, 0));

        Container tabs = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        tabs.setInsets(new Insets3f(0, 0, 12, 0));
        addTab(tabs, app, "PADDLES", "paddle");
        addTab(tabs, app, "TABLES", "table");
        addTab(tabs, app, "BALLS", "ball");
        addTab(tabs, app, "LEVELS", "level");
        addTab(tabs, app, "POWER-UPS", "powerup");

        Container cardsRow = panel.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        cardsRow.setInsets(new Insets3f(0, 0, 16, 0));
        populateCards(cardsRow, app, profile);

        Button close = panel.addChild(new Button("CLOSE"));
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

    private void buildHeader(PaddleShockApp app, float screenW, float screenH) {
        Container headerBar = new Container();
        headerBar.setBackground(quad(Theme.PANEL));
        headerBar.setPreferredSize(new Vector3f(screenW, HEADER_HEIGHT, 0));
        headerBar.setLocalTranslation(0, screenH, 1);
        uiRoot.attachChild(headerBar);

        Label paddleLabel = new Label("PADDLE");
        paddleLabel.setFontSize(26);
        paddleLabel.setColor(Theme.TEXT);
        paddleLabel.setLocalTranslation(28, screenH - 30, 2);
        uiRoot.attachChild(paddleLabel);

        Label shockLabel = new Label("SHOCK");
        shockLabel.setFontSize(26);
        shockLabel.setColor(Theme.ORANGE);
        shockLabel.setLocalTranslation(28 + paddleLabel.getPreferredSize().x, screenH - 30, 2);
        uiRoot.attachChild(shockLabel);

        Container tabs = new Container(new SpringGridLayout(Axis.X, Axis.Y));
        addTab(tabs, app, "PADDLES", "paddle");
        addTab(tabs, app, "TABLES", "table");
        addTab(tabs, app, "BALLS", "ball");
        addTab(tabs, app, "LEVELS", "level");
        addTab(tabs, app, "POWER-UPS", "powerup");
        Vector3f tabsSize = tabs.getPreferredSize();
        tabs.setLocalTranslation((screenW - tabsSize.x) / 2f, screenH - (HEADER_HEIGHT - tabsSize.y) / 2f, 2);
        uiRoot.attachChild(tabs);

        PlayerProfile profile = app.getProfile();
        Label currency = new Label(profile.getCurrency() + " credits");
        currency.setFontSize(18);
        currency.setColor(Theme.ORANGE);
        currency.setBackground(quad(Theme.PANEL_HOVER));
        Vector3f currencySize = currency.getPreferredSize();
        currency.setLocalTranslation(screenW - currencySize.x - 40, screenH - (HEADER_HEIGHT - currencySize.y) / 2f, 2);
        uiRoot.attachChild(currency);
    }

    private void addTab(Container tabs, PaddleShockApp app, String label, String category) {
        Button tab = tabs.addChild(new Button(label));
        tab.setInsets(new Insets3f(4, 6, 4, 6));
        boolean active = category.equals(selectedCategory);
        tab.setBackground(quad(active ? Theme.ORANGE : Theme.PANEL_HOVER));
        tab.setColor(active ? Theme.ON_ACCENT : Theme.TEXT_DIM);
        tab.setFontSize(16);
        tab.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            selectedCategory = category;
            rebuild(app);
        });
    }

    private void buildCards(PaddleShockApp app, float screenW, float screenH) {
        Container cardsRow = new Container(new SpringGridLayout(Axis.X, Axis.Y));
        PlayerProfile profile = app.getProfile();
        populateCards(cardsRow, app, profile);

        Vector3f rowSize = cardsRow.getPreferredSize();
        cardsRow.setLocalTranslation((screenW - rowSize.x) / 2f,
                screenH - HEADER_HEIGHT - 40f, 1);
        uiRoot.attachChild(cardsRow);
    }

    private void populateCards(Container cardsRow, PaddleShockApp app, PlayerProfile profile) {
        switch (selectedCategory) {
            case "paddle" -> {
                for (PaddleDefinition item : Catalog.PADDLES) {
                    String stats = "SPEED " + percent(item.getSpeedMultiplier()) + "   SIZE " + percent(item.getSizeMultiplier());
                    addCard(cardsRow, app, profile, "paddle", item.getId(), item.getDisplayName(),
                            item.getPrice(), item.getColor(), stats);
                }
            }
            case "table" -> {
                for (TableDefinition item : Catalog.TABLES) {
                    String stats = "BOUNCE " + percent(item.getRestitutionMultiplier());
                    addCard(cardsRow, app, profile, "table", item.getId(), item.getDisplayName(),
                            item.getPrice(), item.getSurfaceColor(), stats);
                }
            }
            case "ball" -> {
                for (BallDefinition item : Catalog.BALLS) {
                    String stats = "SPEED " + percent(item.getSpeedMultiplier()) + "   SIZE " + percent(item.getSizeMultiplier());
                    addCard(cardsRow, app, profile, "ball", item.getId(), item.getDisplayName(),
                            item.getPrice(), item.getColor(), stats);
                }
            }
            case "level" -> {
                for (LevelDefinition item : Catalog.LEVELS) {
                    addCard(cardsRow, app, profile, "level", item.getId(), item.getDisplayName(),
                            item.getPrice(), item.getBackdropColor(), item.getTagline());
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

    private void addCard(Container cardsRow, PaddleShockApp app, PlayerProfile profile, String category,
            String id, String displayName, int price, ColorRGBA tint, String statsLine) {

        boolean owned = profile.owns(category, id);
        boolean equipped = owned && profile.getEquippedId(category).equals(id);

        ColorRGBA cardColor = equipped ? mix(Theme.PANEL, Theme.GREEN, 0.12f)
                : owned ? mix(Theme.PANEL, Theme.BLUE, 0.10f)
                : Theme.PANEL;

        Container card = cardsRow.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        card.setInsets(new Insets3f(0, 12, 0, 12));
        card.setBackground(quad(cardColor));
        card.setPreferredSize(new Vector3f(cardWidth, cardHeight, 0));

        Container swatch = card.addChild(new Container());
        swatch.setBackground(quad(tint));
        swatch.setPreferredSize(new Vector3f(cardWidth, swatchHeight, 0));

        Label name = card.addChild(new Label(displayName.toUpperCase()));
        name.setInsets(new Insets3f(10, 16, 4, 16));
        name.setFontSize(20);
        name.setColor(owned || equipped ? Theme.TEXT : Theme.TEXT_DIM);

        String statusText = equipped ? "EQUIPPED" : owned ? "OWNED" : price + " credits";
        Label status = card.addChild(new Label(statusText));
        status.setInsets(new Insets3f(0, 16, 4, 16));
        status.setFontSize(14);
        status.setColor(Theme.TEXT_DIM);

        Label stats = card.addChild(new Label(statsLine));
        stats.setInsets(new Insets3f(4, 16, 14, 16));
        stats.setFontSize(13);
        stats.setColor(Theme.TEXT_DIM);

        String actionLabel = equipped ? "EQUIPPED" : owned ? "EQUIP" : "BUY " + price;
        ColorRGBA actionColor = equipped ? Theme.GREEN : owned ? Theme.BLUE : Theme.ORANGE;

        Button action = card.addChild(new Button(actionLabel));
        action.setInsets(new Insets3f(10, 16, 14, 16));
        action.setBackground(quad(actionColor));
        action.setColor(Theme.ON_ACCENT);
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

    private void addPowerUpCard(Container cardsRow, PaddleShockApp app, PlayerProfile profile, PowerUpDefinition item) {
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

        Container card = cardsRow.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        card.setInsets(new Insets3f(0, 12, 0, 12));
        card.setBackground(quad(cardColor));
        card.setPreferredSize(new Vector3f(cardWidth, cardHeight, 0));

        Container swatch = card.addChild(new Container());
        swatch.setBackground(quad(item.getType().getColor()));
        swatch.setPreferredSize(new Vector3f(cardWidth, swatchHeight, 0));

        Label name = card.addChild(new Label(item.getDisplayName().toUpperCase()));
        name.setInsets(new Insets3f(10, 16, 4, 16));
        name.setFontSize(20);
        name.setColor(owned ? Theme.TEXT : Theme.TEXT_DIM);

        String statusText = assignedSlot >= 0 ? "KEY " + (assignedSlot + 1)
                : owned ? "OWNED" : item.getPrice() + " credits";
        Label status = card.addChild(new Label(statusText));
        status.setInsets(new Insets3f(0, 16, 4, 16));
        status.setFontSize(14);
        status.setColor(Theme.TEXT_DIM);

        String statsLine = "COOLDOWN " + Math.round(item.getCooldownSeconds()) + "s   DURATION "
                + Math.round(item.getType().getDuration()) + "s";
        Label stats = card.addChild(new Label(statsLine));
        stats.setInsets(new Insets3f(4, 16, 14, 16));
        stats.setFontSize(13);
        stats.setColor(Theme.TEXT_DIM);

        if (!owned) {
            Button buy = card.addChild(new Button("BUY " + item.getPrice()));
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
                Button slotButton = slots.addChild(new Button("KEY " + (i + 1)));
                slotButton.setBackground(quad(isThisSlot ? Theme.GREEN : Theme.PANEL_HOVER));
                slotButton.setColor(isThisSlot ? Theme.ON_ACCENT : Theme.TEXT);
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

    private void buildFooter(PaddleShockApp app, float screenW) {
        Container footerBar = new Container();
        footerBar.setBackground(quad(Theme.PANEL));
        footerBar.setPreferredSize(new Vector3f(screenW, FOOTER_HEIGHT, 0));
        footerBar.setLocalTranslation(0, FOOTER_HEIGHT, 1);
        uiRoot.attachChild(footerBar);

        Button back = new Button("< BACK");
        back.setBackground(quad(Theme.PANEL_HOVER));
        back.setColor(Theme.TEXT);
        back.setFontSize(15);
        back.setLocalTranslation(32, FOOTER_HEIGHT - 12, 2);
        back.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            setEnabled(false);
            backAction.run();
        });
        uiRoot.attachChild(back);
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

    @Override
    protected void cleanup(Application application) {
        // uiRoot is detached in onDisable(); nothing else owns native resources here.
    }

    @Override
    protected void onEnable() {
        rebuild((PaddleShockApp) getApplication());
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
