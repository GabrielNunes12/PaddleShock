package com.paddleshock.ui;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.List;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.HAlignment;
import com.simsilica.lemur.Insets3f;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.TextField;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;

import com.paddleshock.app.Navigator;
import com.paddleshock.app.PlayerContext;
import com.paddleshock.data.Friend;
import com.paddleshock.i18n.I18n;

/**
 * Local friends list screen: this player's own id (with a COPY button - the only place it's
 * discoverable in the game besides Profile), an "add friend" row (paste a playerId, type a
 * nickname, ADD), and the current friends list with a REMOVE button per row. Entirely local - see
 * {@code PlayerProfile#getFriends()}; no server call happens on this screen at all. Follows
 * {@link ProfileState}'s single-card structure/style closely - a sibling screen, not a new visual
 * language.
 */
public class FriendsState extends BaseAppState {

    private static final float CARD_WIDTH = 440f;

    private final Node uiRoot = new Node("friendsUi");

    private TextField addIdField;
    private TextField addNicknameField;
    private String addError;

    @Override
    protected void initialize(Application application) {
        // Built fresh in rebuild() every time the screen is shown.
    }

    private void rebuild() {
        uiRoot.detachAllChildren();
        PlayerContext app = (PlayerContext) getApplication();
        SimpleApplication simpleApp = (SimpleApplication) getApplication();
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

        Label title = panel.addChild(new Label(I18n.t("friends.title")));
        title.setFontSize(26);
        title.setColor(Theme.ORANGE);
        title.setInsets(new Insets3f(0, 0, 16, 0));

        Container border = panel.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        border.setBackground(new QuadBackgroundComponent(Theme.PANEL_LINE));
        border.setInsets(new Insets3f(2, 2, 2, 2));
        Container inner = border.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        inner.setBackground(new QuadBackgroundComponent(Theme.PANEL));
        inner.setInsets(new Insets3f(16, 18, 16, 18));

        buildOwnId(app, inner);
        buildDivider(inner);
        buildAddFriendRow(app, inner);
        buildDivider(inner);
        buildFriendsList(app, inner);

        Vector3f currentSize = inner.getPreferredSize();
        inner.setPreferredSize(new Vector3f(CARD_WIDTH, currentSize.y, 0));

        Button back = panel.addChild(new Button(I18n.t("friends.back")));
        styleButton(back, Theme.PANEL_HOVER, Theme.TEXT, 14);
        back.setInsets(new Insets3f(18, 0, 0, 0));
        back.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            ((Navigator) getApplication()).showMainMenu();
        });

        Vector3f panelSize = panel.getPreferredSize();
        panel.setLocalTranslation((screenW - panelSize.x) / 2f, (screenH + panelSize.y) / 2f, 1);
        uiRoot.attachChild(panel);
    }

    private void buildDivider(Container card) {
        Container divider = card.addChild(new Container());
        divider.setBackground(new QuadBackgroundComponent(Theme.PANEL_LINE));
        divider.setPreferredSize(new Vector3f(CARD_WIDTH - 36, 2, 0));
        divider.setInsets(new Insets3f(12, 0, 14, 0));
    }

    /** This player's own id - the only way to actually give it to a friend so they can add you
     *  back (not mutual - see class docs - but they still need to know your id to invite you). */
    private void buildOwnId(PlayerContext app, Container card) {
        Label hint = card.addChild(new Label(I18n.t("friends.your_id_hint")));
        hint.setFontSize(11);
        hint.setColor(Theme.TEXT_DIM);
        hint.setInsets(new Insets3f(0, 0, 6, 0));

        String playerId = app.getProfile().getPlayerId();
        Container row = card.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        Label idLabel = row.addChild(new Label(playerId));
        idLabel.setFontSize(13);
        idLabel.setColor(Theme.TEXT);
        idLabel.setPreferredSize(new Vector3f(CARD_WIDTH - 36 - 80, idLabel.getPreferredSize().y, 0));

        Button copy = row.addChild(new Button(I18n.t("friends.copy")));
        copy.setInsets(new Insets3f(0, 0, 0, 10));
        copy.setBackground(new QuadBackgroundComponent(Theme.PANEL_HOVER));
        copy.setColor(Theme.TEXT);
        copy.setFontSize(13);
        copy.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            copyToClipboard(playerId);
        });
    }

    private void buildAddFriendRow(PlayerContext app, Container card) {
        Label title = card.addChild(new Label(I18n.t("friends.add_a_friend")));
        title.setFontSize(12);
        title.setColor(Theme.TEXT_DIM);
        title.setInsets(new Insets3f(0, 0, 8, 0));

        addIdField = card.addChild(new TextField(""));
        addIdField.setFontSize(14);
        addIdField.setColor(Theme.TEXT);
        addIdField.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND_2));
        addIdField.setPreferredWidth(CARD_WIDTH - 36);
        addIdField.setInsets(new Insets3f(6, 8, 6, 8));
        setPlaceholder(addIdField, I18n.t("friends.placeholder_player_id"));

        addNicknameField = card.addChild(new TextField(""));
        addNicknameField.setFontSize(14);
        addNicknameField.setColor(Theme.TEXT);
        addNicknameField.setBackground(new QuadBackgroundComponent(Theme.BACKGROUND_2));
        addNicknameField.setPreferredWidth(CARD_WIDTH - 36);
        addNicknameField.setInsets(new Insets3f(6, 8, 8, 8));
        setPlaceholder(addNicknameField, I18n.t("friends.placeholder_nickname"));

        if (addError != null) {
            Label error = card.addChild(new Label(addError));
            error.setFontSize(12);
            error.setColor(Theme.ORANGE);
            error.setInsets(new Insets3f(0, 0, 6, 0));
        }

        Button add = card.addChild(new Button(I18n.t("friends.add")));
        add.setBackground(new QuadBackgroundComponent(Theme.BLUE));
        add.setColor(Theme.ON_ACCENT);
        add.setFontSize(14);
        add.setPreferredSize(new Vector3f(CARD_WIDTH - 36, 40, 0));
        add.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            attemptAddFriend(app);
        });
    }

    /** Lemur's {@link TextField} has no built-in placeholder support - a plain empty-initial-text
     *  field is used instead (matching how every other TextField in this codebase, e.g. ProfileState's
     *  name field, is just seeded with real content rather than placeholder text); the field's
     *  starting text below doubles as an inline hint that the player types over. */
    private void setPlaceholder(TextField field, String hint) {
        field.setText(hint);
    }

    private void attemptAddFriend(PlayerContext app) {
        String rawId = addIdField.getText() == null ? "" : addIdField.getText().trim();
        String rawNickname = addNicknameField.getText() == null ? "" : addNicknameField.getText().trim();
        if (rawId.isEmpty() || I18n.t("friends.placeholder_player_id").equals(rawId)) {
            addError = I18n.t("friends.error_enter_id");
            rebuild();
            return;
        }
        if (rawId.equals(app.getProfile().getPlayerId())) {
            addError = I18n.t("friends.error_own_id");
            rebuild();
            return;
        }
        String nickname = I18n.t("friends.placeholder_nickname").equals(rawNickname) ? "" : rawNickname;
        app.getProfile().addFriend(rawId, nickname);
        app.saveProfile();
        addError = null;
        rebuild();
    }

    private void buildFriendsList(PlayerContext app, Container card) {
        Label title = card.addChild(new Label(I18n.t("friends.your_friends")));
        title.setFontSize(12);
        title.setColor(Theme.TEXT_DIM);
        title.setInsets(new Insets3f(0, 0, 8, 0));

        List<Friend> friendsList = app.getProfile().getFriends();
        if (friendsList.isEmpty()) {
            Label empty = card.addChild(new Label(I18n.t("friends.no_friends_yet")));
            empty.setFontSize(14);
            empty.setColor(Theme.TEXT_DIM);
            empty.setTextHAlignment(HAlignment.Center);
            empty.setPreferredSize(new Vector3f(CARD_WIDTH - 36, 40, 0));
            return;
        }

        Container rows = card.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        for (Friend friend : friendsList) {
            addFriendRow(app, rows, friend);
        }
    }

    private void addFriendRow(PlayerContext app, Container rows, Friend friend) {
        Container row = rows.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
        row.setInsets(new Insets3f(3, 0, 3, 0));

        Container nameBlock = row.addChild(new Container(new SpringGridLayout(Axis.Y, Axis.X)));
        Label nameLabel = nameBlock.addChild(new Label(friend.getNickname()));
        nameLabel.setFontSize(14);
        nameLabel.setColor(Theme.TEXT);
        Label idLabel = nameBlock.addChild(new Label(friend.shortId()));
        idLabel.setFontSize(11);
        idLabel.setColor(Theme.TEXT_DIM);
        nameBlock.setPreferredSize(new Vector3f(CARD_WIDTH - 36 - 90, nameBlock.getPreferredSize().y, 0));

        Button remove = row.addChild(new Button(I18n.t("friends.remove")));
        remove.setBackground(new QuadBackgroundComponent(Theme.PANEL_HOVER));
        remove.setColor(Theme.TEXT_DIM);
        remove.setFontSize(12);
        remove.addClickCommands(source -> {
            app.getAudioManager().playSfx("button_click.ogg");
            app.getProfile().removeFriend(friend.getPlayerId());
            app.saveProfile();
            rebuild();
        });
    }

    private void copyToClipboard(String text) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        } catch (Exception e) {
            // Clipboard access can fail in some sandboxed/headless environments - not worth
            // surfacing an error for a convenience feature; the id is still shown on screen.
        }
    }

    private void styleButton(Button button, com.jme3.math.ColorRGBA bg, com.jme3.math.ColorRGBA fg, int fontSize) {
        button.setInsets(new Insets3f(6, 0, 6, 0));
        button.setBackground(new QuadBackgroundComponent(bg));
        button.setColor(fg);
        button.setFontSize(fontSize);
        button.setPreferredSize(new Vector3f(340, 46, 0));
    }

    @Override
    protected void cleanup(Application application) {
        // uiRoot is detached in onDisable(); nothing else owns native resources here.
    }

    @Override
    protected void onEnable() {
        addError = null;
        rebuild();
        ((SimpleApplication) getApplication()).getGuiNode().attachChild(uiRoot);
        getApplication().getInputManager().setCursorVisible(true);
    }

    @Override
    protected void onDisable() {
        uiRoot.removeFromParent();
    }
}
