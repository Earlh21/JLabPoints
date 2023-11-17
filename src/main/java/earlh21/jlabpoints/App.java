package earlh21.jlabpoints;

import net.dv8tion.jda.api.JDABuilder;

import javax.sql.DataSource;
import java.io.File;
import java.sql.SQLException;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.apache.commons.dbcp2.BasicDataSource;

/**
 * Hello world!
 *
 */
public class App 
{
    public static void main( String[] args ) throws SQLException, InterruptedException {
        String dataFolder = System.getProperty("user.home") + "/AppData/Local/jlabpoints";

        boolean shouldCreateSchema = !new File(dataFolder + "/data.mv.db").exists();
        DataSource dataSource = setupDataSource("jdbc:h2:file:" + dataFolder + "/data");

        if(shouldCreateSchema) {
            createSchema(dataSource);
        }

        var token = System.getenv("jlabpoints_token");
        var jda = JDABuilder.createDefault(token)
                .addEventListeners(new GameCommands(dataSource))
                .build();

        jda.updateCommands().addCommands(
                Commands.slash("query", "Check someone's points")
                        .addOption(OptionType.USER, "user", "The user to check", true),
                Commands.slash("leaderboard", "See the top players"),
                Commands.slash("addpoints", "Give someone points")
                        .addOption(OptionType.USER, "user", "The user to give points to", true)
                        .addOption(OptionType.INTEGER, "points", "The number of points to give", true),
                Commands.slash("addplayer", "Add a user to the game")
                        .addOption(OptionType.USER, "user", "The user to add", true),
                Commands.slash("removeplayer", "Remove a player from the game")
                        .addOption(OptionType.USER, "user", "The player to remove", true),
                Commands.slash("setpointmaster", "Set the point master status of a player")
                        .addOption(OptionType.USER, "user", "The player to modify", true)
                        .addOption(OptionType.BOOLEAN, "value", "Whether or not the player should be a point master", true)
        ).queue();

        jda.awaitReady();
    }

    public static DataSource setupDataSource(String connectURI) {
        BasicDataSource ds = new BasicDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl(connectURI);
        return ds;
    }

    public static void createSchema(DataSource dataSource) throws SQLException {
        var connection = dataSource.getConnection();
        var statement = connection.createStatement();
        statement.execute("create table player (id bigint primary key, points int not null, point_master bool, admin bool)");
        connection.commit();

        statement.execute("create table role (id bigint primary key, guild_id bigint)");

        statement.execute("create table player_role (player_id bigint, role_id bigint, primary key (player_id, role_id), foreign key (player_id) references player(id), foreign key (role_id) references role(id))");
        connection.commit();
    }
}
