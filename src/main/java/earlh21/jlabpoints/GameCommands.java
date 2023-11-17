package earlh21.jlabpoints;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.apache.commons.logging.Log;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GameCommands extends ListenerAdapter {
    private final static Logger logger = LoggerFactory.getLogger(GameCommands.class);

    private final DataSource dataSource;
    private final Map<Long, Integer> rerollsLeft = new HashMap<>();
    private final Map<Long, Long> currentRoll = new HashMap<>();

    public GameCommands(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        try {
            switch (event.getName()) {
                case "query":
                    queryCommand(event);
                    break;
                case "leaderboard":
                    leaderboardCommand(event);
                    break;
                case "addpoints":
                    addPointsCommand(event);
                    break;
                case "addplayer":
                    addPlayerCommand(event);
                    break;
                case "removeplayer":
                    removePlayerCommand(event);
                    break;
                case "setpointmaster":
                    setPointMaster(event);
                    break;
                default:
                    event.reply("Command not found.").queue();
            }
        } catch (SQLException e) {
            event.reply("SQL error occurred.").queue();
            logger.error("SQL error in command", e);
        }
    }

    private void setRoleCommand(SlashCommandInteractionEvent event) throws SQLException {
        var connection = dataSource.getConnection();

        var user = event.getUser();
        var guild = event.getGuild();
        var role = event.getOption("role").getAsRole();

        if(!isUserPlayer(connection, user.getIdLong())) {
            event.reply("You're not a player.").queue();
            return;
        }

        var statement = connection.prepareStatement("select guild_id from role where id = ?");
        statement.setLong(1, role.getIdLong());

        var rs = statement.executeQuery();
        if(!rs.first()) {
            event.reply("That role isn't in the game.").queue();
            return;
        }

        if(rs.getLong(1) != guild.getIdLong()) {
            event.reply("You're in the wrong server for that role.").queue();
            return;
        }

        statement = connection.prepareStatement("select 1 from player_role where player_id = ? and role_id = ?");
        statement.setLong(1, user.getIdLong());
        statement.setLong(2, role.getIdLong());

        rs = statement.executeQuery();
        if(!rs.first()) {
            event.reply("You don't have that role.").queue();
            return;
        }


    }

    private void addRoleCommand(SlashCommandInteractionEvent event) throws SQLException {
        var connection = dataSource.getConnection();

        var user = event.getUser();
        var guild = event.getGuild();
        var role = event.getOption("role").getAsRole();

        if(!isUserAdmin(connection, user.getIdLong())) {
            event.reply("You aren't an admin.").queue();
            return;
        }

        var statement = connection.prepareStatement("select 1 from role where id = ?");
        statement.setLong(1, role.getIdLong());

        var rs = statement.executeQuery();
        if(rs.first()) {
            event.reply("Role has already been added.").queue();
            return;
        }

        statement = connection.prepareStatement("insert into role (id, guild_id) values (?, ?)");
        statement.setLong(1, user.getIdLong());
        statement.setLong(2, guild.getIdLong());
        statement.execute();

        connection.commit();

        event.reply("Role has been added.").queue();
    }

    private void acceptCommand(SlashCommandInteractionEvent event) throws SQLException {
        var connection = dataSource.getConnection();
        var user = event.getUser();

        if(!currentRoll.containsKey(user.getIdLong())) {
            event.reply("You aren't ranking up right now.").queue();
            return;
        }

        finalizeRoll(connection, user.getIdLong());
        event.reply("Enjoy your role!").queue();
    }

    private void rerollCommand(SlashCommandInteractionEvent event) throws SQLException {
        var connection = dataSource.getConnection();
        var user = event.getUser();
        var guild = event.getGuild();

        if(!currentRoll.containsKey(user.getIdLong())) {
            event.reply("You aren't ranking up right now.").queue();
            return;
        }

        var randomRole = getRandomNewRole(connection, user.getIdLong(), guild.getIdLong());

        currentRoll.put(user.getIdLong(), randomRole);
        rerollsLeft.put(user.getIdLong(), rerollsLeft.get(user.getIdLong() - 1));

        if(rerollsLeft.get(user.getIdLong()) < 1) {
            finalizeRoll(connection, user.getIdLong());
            event.reply("You rolled <@" + randomRole + ">. You're out of rerolls, so I hope you like it!").queue();
        } else {
            event.reply("You rolled <@" + randomRole + ">. You have " + rerollsLeft.get(user.getIdLong()) + " rerolls left.").queue();
        }
    }

    private void rankupCommand(SlashCommandInteractionEvent event) throws SQLException {
        var connection = dataSource.getConnection();

        var user = event.getUser();
        var guild = event.getGuild();

        var statement = connection.prepareStatement("select points from player where id = ?");
        statement.setLong(1, user.getIdLong());

        var rs = statement.executeQuery();
        if(!rs.first()) {
            event.reply("You're not a player.").queue();
            return;
        }

        var currentPoints = rs.getInt(1);
        int rank = currentPoints / 5 + 1;

        statement = connection.prepareStatement("select count(role_id) from player_role where player_id = ?");
        statement.setLong(1, user.getIdLong());

        rs = statement.executeQuery();
        var currentRoleCount = rs.getInt(1);

        if(currentRoleCount >= rank) {
            event.reply("You can't rank up yet.").queue();
            return;
        }

        var randomRole = getRandomNewRole(connection, user.getIdLong(), guild.getIdLong());

        if(randomRole == null) {
            event.reply("You already have every role!").queue();
            return;
        }

        currentRoll.put(user.getIdLong(), randomRole);
        rerollsLeft.put(user.getIdLong(), 2);

        event.reply("You rolled <@" + randomRole + ">. You have " + 2 + " rerolls left.").queue();
    }

    private void removePlayerCommand(SlashCommandInteractionEvent event) throws SQLException {
        var connection = dataSource.getConnection();

        var user = event.getUser();
        var targetUser = event.getOption("user").getAsUser();

        if(!isUserAdmin(connection, user.getIdLong())) {
            event.reply("You aren't an admin.").queue();
            return;
        }

        if(!isUserPlayer(connection, targetUser.getIdLong())) {
            event.reply("This user hasn't been added to the game.").queue();
            return;
        }

        var statement = connection.prepareStatement("delete from player where id = ?");
        statement.setLong(1, targetUser.getIdLong());
        statement.execute();

        connection.commit();

        event.reply("Player has been... removed.").queue();
    }

    private void setPointMaster(SlashCommandInteractionEvent event) throws SQLException {
        var connection = dataSource.getConnection();

        var user = event.getUser();
        var targetUser = event.getOption("user").getAsUser();
        var value = event.getOption("value").getAsBoolean();

        if(!isUserAdmin(connection, user.getIdLong())) {
            event.reply("You aren't an admin.").queue();
            return;
        }

        if(!isUserPlayer(connection, targetUser.getIdLong())) {
            event.reply("This user hasn't been added to the game.").queue();
            return;
        }

        var statement = connection.prepareStatement("update player set point_master = ? where id = ?");
        statement.setBoolean(1, value);
        statement.setLong(2, targetUser.getIdLong());
        statement.execute();

        connection.commit();

        if(value) {
            event.reply("<@" + targetUser.getIdLong() + "> is now a point master.").queue();
        } else {
            event.reply("<@" + targetUser.getIdLong() + "> is no longer a point master.").queue();
        }
    }

    private void leaderboardCommand(SlashCommandInteractionEvent event) throws SQLException {
        var connection = dataSource.getConnection();

        var statement = connection.createStatement();
        var rs = statement.executeQuery("select top 5 id, points from player order by points desc");

        var message = new StringBuilder();

        while(rs.next()) {
            message.append("<@").append(rs.getLong(1)).append("> has ").append(rs.getInt(2)).append(" points.\n");
        }

        event.reply(message.toString()).queue();
    }

    private void queryCommand(SlashCommandInteractionEvent event) throws SQLException {
        var connection = dataSource.getConnection();

        var targetUser = event.getOption("user").getAsUser();

        var statement = connection.prepareStatement("select points from player where id = ?");
        statement.setLong(1, targetUser.getIdLong());

        var rs = statement.executeQuery();
        if(!rs.first()) {
            event.reply("This user hasn't been added to the game.").queue();
            return;
        }

        event.reply("<@" + targetUser.getIdLong() + "> has " + rs.getInt(1) + " points.").queue();
    }

    private void addPointsCommand(SlashCommandInteractionEvent event) throws SQLException {
        var connection = dataSource.getConnection();

        var user = event.getUser();
        var targetUser = event.getOption("user").getAsUser();
        var points = event.getOption("points").getAsInt();

        if(!isUserPointMaster(connection, user.getIdLong())) {
            event.reply("You aren't the point master.").queue();
            return;
        }

        var statement = connection.prepareStatement("select points from player where id = ?");
        statement.setLong(1, targetUser.getIdLong());

        var rs = statement.executeQuery();
        if(!rs.first()) {
            event.reply("This user hasn't been added to the game.").queue();
            return;
        }

        var newPoints = rs.getInt(1) + points;

        statement = connection.prepareStatement("update player set points = ? where id = ?");
        statement.setInt(1, newPoints);
        statement.setLong(2, targetUser.getIdLong());
        statement.execute();

        connection.commit();

        event.reply("<@" + targetUser.getIdLong() + "> now has " + newPoints + " points.").queue();
    }

    private void addPlayerCommand(SlashCommandInteractionEvent event) throws SQLException {
        var connection = dataSource.getConnection();

        var user = event.getUser();
        var targetUser = event.getOption("user").getAsUser();

        if(!isUserAdmin(connection, user.getIdLong())) {
            event.reply("You aren't an admin.").queue();
            return;
        }

        if(isUserPlayer(connection, targetUser.getIdLong())) {
            event.reply("This user has already been added to the game.").queue();
            return;
        }

        var statement = connection.prepareStatement("insert into player (id, points, point_master, admin) values (?, 0, false, false)");
        statement.setLong(1, targetUser.getIdLong());
        statement.execute();

        connection.commit();

        event.reply("Player was added.").queue();
    }

    private void finalizeRoll(Connection connection, long id) throws SQLException {
        if(!currentRoll.containsKey(id)) {
            throw new IllegalArgumentException("User isn't ranking up right now.");
        }

        var statement = connection.prepareStatement("insert into player_role (player_id, role_id) values (?, ?)");
        statement.setLong(1, id);
        statement.setLong(2, currentRoll.get(id));
        statement.execute();

        connection.commit();

        rerollsLeft.remove(id);
        currentRoll.remove(id);
    }

    private Long getRandomNewRole(Connection connection, long userId, long guildId) throws SQLException {
        var statement = connection.prepareStatement("select id from role where id not in (select role_id from player_role where player_id = ?) and guild_id = ?  order by random() limit 1");
        statement.setLong(1, userId);
        statement.setLong(2, guildId);

        var rs = statement.executeQuery();
        if(!rs.first()) {
            return null;
        }

        return rs.getLong(1);
    }

    private boolean isUserPointMaster(Connection connection, long id) throws SQLException {
        var statement = connection.prepareStatement("select point_master or admin from player where id = ?");
        statement.setLong(1, id);

        var rs = statement.executeQuery();
        return rs.first() && rs.getBoolean(1);
    }

    private boolean isUserAdmin(Connection connection, long id) throws SQLException {
        var statement = connection.prepareStatement("select admin from player where id = ?");
        statement.setLong(1, id);

        var rs = statement.executeQuery();
        return rs.first() && rs.getBoolean(1);
    }

    private boolean isUserPlayer(Connection connection, long id) throws SQLException {
        var statement = connection.prepareStatement("select 1 from player where id = ?");
        statement.setLong(1, id);

        return statement.executeQuery().first();
    }
}
