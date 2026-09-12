package com.paddleshock.ui;

import java.util.List;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.component.SpringGridLayout;

import com.paddleshock.app.PaddleShockApp;
import com.paddleshock.data.BallDefinition;
import com.paddleshock.data.Catalog;
import com.paddleshock.data.ItemDefinition;
import com.paddleshock.data.PaddleDefinition;
import com.paddleshock.data.PlayerProfile;
import com.paddleshock.data.TableDefinition;

public class StoreState extends BaseAppState {

    private Container window;
    private Label currencyLabel;

    @Override
    protected void initialize(Application application) {
        window = new Container();
        rebuild((PaddleShockApp) application);
        centerWindow((SimpleApplication) application);
    }

    private void rebuild(PaddleShockApp app) {
        window.clearChildren();
        window.addChild(new Label("Store"));
        currencyLabel = window.addChild(new Label(""));

        addSection(window, app, "Paddles", "paddle", Catalog.PADDLES,
                item -> ((PaddleDefinition) item).getDisplayName());
        addSection(window, app, "Tables", "table", Catalog.TABLES,
                item -> ((TableDefinition) item).getDisplayName());
        addSection(window, app, "Balls", "ball", Catalog.BALLS,
                item -> ((BallDefinition) item).getDisplayName());

        Button back = window.addChild(new Button("Back"));
        back.addClickCommands(source -> app.showMainMenu());

        refreshCurrency(app.getProfile());
    }

    private <T extends ItemDefinition> void addSection(Container parent, PaddleShockApp app, String title,
            String category, List<T> items, java.util.function.Function<ItemDefinition, String> nameFn) {
        parent.addChild(new Label(title));
        PlayerProfile profile = app.getProfile();

        for (T item : items) {
            Container row = parent.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y)));
            row.addChild(new Label(nameFn.apply(item) + " (" + item.getPrice() + ")"));

            boolean owned = profile.owns(category, item.getId());
            boolean equipped = owned && profile.getEquippedId(category).equals(item.getId());

            String actionLabel = equipped ? "Equipped" : owned ? "Equip" : "Buy";
            Button action = row.addChild(new Button(actionLabel));
            action.setEnabled(!equipped);
            action.addClickCommands(source -> {
                if (owned) {
                    profile.equip(category, item.getId());
                } else {
                    profile.purchase(category, item.getId(), item.getPrice());
                }
                app.saveProfile();
                rebuild(app);
            });
        }
    }

    private void refreshCurrency(PlayerProfile profile) {
        currencyLabel.setText("Currency: " + profile.getCurrency());
    }

    private void centerWindow(SimpleApplication app) {
        window.setLocalTranslation(
                app.getCamera().getWidth() / 2f - 220,
                app.getCamera().getHeight() / 2f + 240,
                0);
    }

    @Override
    protected void cleanup(Application application) {
        // Container is detached in onDisable(); nothing else owns native resources here.
    }

    @Override
    protected void onEnable() {
        rebuild((PaddleShockApp) getApplication());
        ((SimpleApplication) getApplication()).getGuiNode().attachChild(window);
        getApplication().getInputManager().setCursorVisible(true);
    }

    @Override
    protected void onDisable() {
        window.removeFromParent();
    }
}
