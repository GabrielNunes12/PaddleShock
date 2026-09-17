package com.paddleshock.net;

import java.io.IOException;
import java.util.List;

import com.paddleshock.net.InviteClient.Invite;

/**
 * Direct-invite mailbox actions - see {@link InviteClient} (the default implementation) for the
 * actual network behavior/docs. Exists so callers depend on an interface rather than a static
 * class, making them unit-testable/swappable.
 */
public interface InviteService {

    void sendInvite(String fromPlayerId, String fromNameHint, String toPlayerId, String lobbyCode) throws IOException;

    List<Invite> getInvites(String playerId) throws IOException;

    void dismissInvites(String playerId) throws IOException;
}
