package com.jcloisterzone.ui;

import static com.jcloisterzone.ui.I18nUtils._;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jcloisterzone.Expansion;
import com.jcloisterzone.config.Config.AutostartConfig;
import com.jcloisterzone.config.Config.DebugConfig;
import com.jcloisterzone.config.Config.PresetConfig;
import com.jcloisterzone.event.ChatEvent;
import com.jcloisterzone.event.ClientListChangedEvent;
import com.jcloisterzone.event.GameListChangedEvent;
import com.jcloisterzone.event.setup.ExpansionChangedEvent;
import com.jcloisterzone.event.setup.PlayerSlotChangeEvent;
import com.jcloisterzone.event.setup.RuleChangeEvent;
import com.jcloisterzone.game.CustomRule;
import com.jcloisterzone.game.Game;
import com.jcloisterzone.game.PlayerSlot;
import com.jcloisterzone.game.PlayerSlot.SlotState;
import com.jcloisterzone.game.Snapshot;
import com.jcloisterzone.game.phase.CreateGamePhase;
import com.jcloisterzone.game.phase.LoadGamePhase;
import com.jcloisterzone.game.phase.Phase;
import com.jcloisterzone.online.Channel;
import com.jcloisterzone.ui.view.ChannelView;
import com.jcloisterzone.ui.view.GameSetupView;
import com.jcloisterzone.wsio.Connection;
import com.jcloisterzone.wsio.MessageDispatcher;
import com.jcloisterzone.wsio.MessageListener;
import com.jcloisterzone.wsio.RmiProxy;
import com.jcloisterzone.wsio.WsSubscribe;
import com.jcloisterzone.wsio.message.ChannelMessage;
import com.jcloisterzone.wsio.message.ChatMessage;
import com.jcloisterzone.wsio.message.ClientListMessage;
import com.jcloisterzone.wsio.message.ErrorMessage;
import com.jcloisterzone.wsio.message.GameListMessage;
import com.jcloisterzone.wsio.message.GameMessage;
import com.jcloisterzone.wsio.message.GameSetupMessage;
import com.jcloisterzone.wsio.message.RmiMessage;
import com.jcloisterzone.wsio.message.SetExpansionMessage;
import com.jcloisterzone.wsio.message.SetRuleMessage;
import com.jcloisterzone.wsio.message.SlotMessage;
import com.jcloisterzone.wsio.message.StartGameMessage;
import com.jcloisterzone.wsio.message.TakeSlotMessage;
import com.jcloisterzone.wsio.message.UndoMessage;
import com.jcloisterzone.wsio.message.WsInChannelMessage;
import com.jcloisterzone.wsio.message.WsInGameMessage;
import com.jcloisterzone.wsio.message.WsMessage;
import com.jcloisterzone.wsio.server.RemoteClient;


public class ClientMessageListener implements MessageListener {

    protected final transient Logger logger = LoggerFactory.getLogger(getClass());

    private Connection conn;
    private MessageDispatcher dispatcher = new MessageDispatcher();

    private Map<String, GameController> gameControllers = new HashMap<>();
    private Map<String, ChannelController> channelControllers = new HashMap<>();

    private final boolean playOnline;
    private final Client client;
    private boolean autostartPerfomed;

    public ClientMessageListener(Client client, boolean playOnline) {
        this.client = client;
        this.playOnline = playOnline;
    }

    public Connection connect(String username, URI uri) {
        conn = new Connection(username, client.getConfig(), uri, this);
        return conn;
    }

    public boolean isPlayOnline() {
        return playOnline;
    }

    public Connection getConnection() {
        return conn;
    }

    @Override
    public void onWebsocketError(Exception ex) {
        client.onWebsocketError(ex);
    }

    @Override
    public void onWebsocketClose(int code, String reason, boolean remote) {
        //empty for now
    }

    @Override
    public void onWebsocketMessage(WsMessage msg) {
        //TODO pass game as context to dispatch
        EventProxyUiController<?> controller = getController(msg);
        if (controller instanceof GameController) {
            GameController gc = (GameController) controller;
            dispatcher.dispatch(msg, conn, this, gc.getGame().getPhase());
            gc.phaseLoop();
        } else {
            dispatcher.dispatch(msg, conn, this);
        }
    }

    public RemoteClient getClientBySessionId(EventProxyUiController<?> controller, String sessionId) {;
        for (RemoteClient remote: controller.getRemoteClients()) {
            if (remote.getSessionId().equals(sessionId)) {
                return remote;
            }
        }
        throw new NoSuchElementException();
    }

    private EventProxyUiController<?> getController(WsMessage msg) {
        if (msg instanceof WsInGameMessage) {
            GameController gc = gameControllers.get(((WsInGameMessage) msg).getGameId());
            if (gc != null) return gc;
        }
        if (msg instanceof WsInChannelMessage) {
            ChannelController cc = channelControllers.get(((WsInChannelMessage) msg).getChannel());
            if (cc != null) return cc;
        }
        return null;
    }

    private Game getGame(WsInGameMessage msg) {
        GameController gc = (GameController) getController(msg);
        return gc == null ? null : gc.getGame();
    }

    private void updateSlot(PlayerSlot[] slots, SlotMessage slotMsg) {
        PlayerSlot slot = slots[slotMsg.getNumber()];
        slot.setNickname(slotMsg.getNickname());
        slot.setSessionId(slotMsg.getOwner());
        if (slotMsg.getOwner() == null) {
            slot.setState(SlotState.OPEN);
        } else {
            slot.setState(slotMsg.getOwner().equals(conn.getSessionId()) ? SlotState.OWN : SlotState.REMOTE);
        }
        slot.setSerial(slotMsg.getSerial());
        slot.setAiClassName(slotMsg.getAiClassName());
    }



    private GameController createGameController(GameMessage msg) {
        Snapshot snapshot = null;
        if (msg.getSnapshot() != null) {
            try {
                snapshot = new Snapshot(msg.getSnapshot());
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }

        Game game;
        GameController gc;
        CreateGamePhase phase;
        if (snapshot == null) {
            game = new Game(msg.getGameId());
            game.setName(msg.getName());
            gc = new GameController(client, game);
            phase = new CreateGamePhase(game, gc);
        } else {
            game = snapshot.asGame(msg.getGameId());
            gc = new GameController(client, game);
            phase = new LoadGamePhase(game, snapshot, gc);
        }
        gc.setReportingTool(conn.getReportingTool());
        gc.setChannel(msg.getChannel());
        PlayerSlot[] slots = new PlayerSlot[PlayerSlot.COUNT];
        for (SlotMessage slotMsg : msg.getSlots()) {
            int number = slotMsg.getNumber();
            PlayerSlot slot = new PlayerSlot(number);
            slot.setColors(client.getConfig().getPlayerColor(slot));
            slots[number] = slot;
            updateSlot(slots, slotMsg);
        }
        phase.setSlots(slots);
        game.getPhases().put(phase.getClass(), phase);
        game.setPhase(phase);
        return gc;
    }

    private void handleGameStarted(GameController gc) {
        conn.getReportingTool().setGame(gc.getGame());
        CreateGamePhase phase = (CreateGamePhase)gc.getGame().getPhase();
        phase.startGame();
    }

    private void openGameSetup(final GameController gc, final GameMessage msg) throws InvocationTargetException, InterruptedException {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                client.mountView(new GameSetupView(client, gc, msg.getSnapshot() == null));
                performAutostart(gc.getGame()); //must wait for panel is created
            }
        });
    }

    @WsSubscribe
    public void handleGame(final GameMessage msg) throws InvocationTargetException, InterruptedException {
        handleGame(msg, false);
    }

    public GameController handleGame(final GameMessage msg, boolean channelList) throws InvocationTargetException, InterruptedException {
        msg.getGameSetup().setGameId(msg.getGameId());  //fill omitted id
        for (SlotMessage slotMsg : msg.getSlots()) {
            slotMsg.setGameId(msg.getGameId()); //fill omitted id
        }

        GameController gc = (GameController) getController(msg);
        if (gc == null) {
            gc = createGameController(msg);
            //TODO remove games on game over
            gameControllers.put(msg.getGameId(), gc);
        }

        handleGameSetup(msg.getGameSetup());
        for (SlotMessage slotMsg : msg.getSlots()) {
            handleSlot(slotMsg);
        }

        if (!channelList) {
            switch (msg.getState()) {
            case OPEN:
                openGameSetup(gc, msg);
                break;
            case RUNNING:
                handleGameStarted(gc);
                break;
            }
        }
        return gc;
    }

    @WsSubscribe
    public void handleChannel(final ChannelMessage msg) throws InvocationTargetException, InterruptedException {
        Channel channel = new Channel(msg.getName());
        final ChannelController channelController = new ChannelController(client, channel);
        channelControllers.clear();
        channelControllers.put(channel.getName(), channelController);
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                client.mountView(new ChannelView(client, channelController));
            }
        });
    }

    @WsSubscribe
    public void handleClientList(ClientListMessage msg) {
        EventProxyUiController<?> controller = getController(msg);
        if (controller != null) {
            controller.setRemoteClients(msg.getClients());
            controller.getEventProxy().post(new ClientListChangedEvent(msg.getClients()));
        } else {
            logger.warn("No controller for message {}", msg);
        }
    }

    @WsSubscribe
    public void handleGameList(GameListMessage msg) throws InvocationTargetException, InterruptedException {
        ChannelController cc = (ChannelController) getController(msg);
        GameController[] gameControllers = new GameController[msg.getGames().length];
        int i = 0;
        for (GameMessage gameMsg : msg.getGames()) {
            gameControllers[i++] = handleGame(gameMsg, true);
        }
        cc.getEventProxy().post(new GameListChangedEvent(gameControllers));
    }

    @WsSubscribe
    public void handleChat(ChatMessage msg) {
        EventProxyUiController<?> controller = getController(msg);
        if (controller != null) {
            ChatEvent ev = new ChatEvent(getClientBySessionId(controller, msg.getSessionId()), msg.getText());
            controller.getEventProxy().post(ev);
        } else {
            logger.warn("No controller for message {}", msg);
        }
    }

    @WsSubscribe
    public void handleSlot(SlotMessage msg) {
        Game game = getGame(msg);
        PlayerSlot[] slots = ((CreateGamePhase) game.getPhase()).getPlayerSlots();
        updateSlot(slots, msg);
        game.post(new PlayerSlotChangeEvent(slots[msg.getNumber()]));
    }

    @WsSubscribe
    public void handleGameSetup(GameSetupMessage msg) {
        Game game = getGame(msg);
        game.getExpansions().clear();
        game.getExpansions().addAll(msg.getExpansions());
        game.getCustomRules().clear();
        game.getCustomRules().addAll(msg.getCustomRules());

        for (Expansion exp : Expansion.values()) {
            if (!exp.isImplemented()) continue;
            game.post(new ExpansionChangedEvent(exp, game.getExpansions().contains(exp)));
        }
        for (CustomRule rule : CustomRule.values()) {
            game.post(new RuleChangeEvent(rule, game.getCustomRules().contains(rule)));
        }
    }


    @WsSubscribe
    public void handleSetExpansion(SetExpansionMessage msg) {
        Game game = getGame(msg);
        Expansion expansion = msg.getExpansion();
        if (msg.isEnabled()) {
            game.getExpansions().add(expansion);
        } else {
            game.getExpansions().remove(expansion);
        }
        game.post(new ExpansionChangedEvent(expansion, msg.isEnabled()));
    }

    @WsSubscribe
    public void handleSetRule(SetRuleMessage msg) {
        Game game = getGame(msg);
        CustomRule rule = msg.getRule();
        if (msg.isEnabled()) {
            game.getCustomRules().add(rule);
        } else {
            game.getCustomRules().remove(rule);
        }
        game.post(new RuleChangeEvent(rule, msg.isEnabled()));
    }

    @WsSubscribe
    public void handleRmi(RmiMessage msg) {
        Game game = getGame(msg);
        try {
            Phase phase = game.getPhase();
            Method[] methods = RmiProxy.class.getMethods();
            for (int i = 0; i < methods.length; i++) {
                if (methods[i].getName().equals(msg.getMethod())) {
                    methods[i].invoke(phase, (Object[]) msg.decode(msg.getArgs()));
                    return;
                }
            }
        } catch (InvocationTargetException ie) {
            logger.error(ie.getMessage(), ie.getCause());
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    @WsSubscribe
    public void handleUndo(UndoMessage msg) {
        Game game = getGame(msg);
        game.undo();
    }

    @WsSubscribe
    public void handleError(Connection conn, ErrorMessage err) {
        switch (err.getCode()) {
        case ErrorMessage.BAD_VERSION:
            logger.warn(err.getMessage());
            JOptionPane.showMessageDialog(client,
                    _("Remote JCloisterZone is not compatible with local application. Please upgrade both applications to same version."),
                    _("Incompatible versions"),
                    JOptionPane.ERROR_MESSAGE
            );
            break;
        default:
            logger.error(err.getMessage());
        }
    }

    public String getSessionId() {
        return conn.getSessionId();
    }


  protected void performAutostart(Game game) {
      DebugConfig debugConfig = client.getConfig().getDebug();
      if (!autostartPerfomed && debugConfig != null && debugConfig.isAutostartEnabled()) {
          autostartPerfomed = true; //apply autostart only once
          AutostartConfig autostartConfig = debugConfig.getAutostart();
          final PresetConfig presetCfg = client.getConfig().getPresets().get(autostartConfig.getPreset());
          if (presetCfg == null) {
              logger.warn("Autostart profile {} not found.", autostartConfig.getPreset());
              return;
          }

          final List<String> players = autostartConfig.getPlayers() == null ? new ArrayList<String>() : autostartConfig.getPlayers();
          if (players.isEmpty()) {
              players.add("Player");
          }

          int i = 0;
          for (String name: players) {
              Class<?> clazz = null;;
              PlayerSlot slot =  ((CreateGamePhase) game.getPhase()).getPlayerSlots()[i];
              try {
                  clazz = Class.forName(name);
                  slot.setAiClassName(name);
                  name = "AI-" + i + "-" + clazz.getSimpleName().replace("AiPlayer", "");
              } catch (ClassNotFoundException e) {
                  //empty
              }
              TakeSlotMessage msg = new TakeSlotMessage(game.getGameId(), i, name);
              if (slot.getAiClassName() != null) {
                  msg.setAiClassName(slot.getAiClassName());
              }
              conn.send(msg);
              i++;
          }

          presetCfg.updateGameSetup(conn, game.getGameId());
          conn.send(new StartGameMessage(game.getGameId()));
      }
  }

}
