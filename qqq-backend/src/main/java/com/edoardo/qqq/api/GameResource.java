package com.edoardo.qqq.api;

/*
 * GameResource.java
 * Author: Edoardo Sabatini
 * Date: 2026-03-31
 * 
 * This class defines the REST API for the game logic. 
 * It receives the current game state from the Svelte frontend, 
 * constructs a prompt for the local QWEN model, and returns the next move as.
 * 
 */

import io.smallrye.common.annotation.Blocking;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Random;

@Path("/game")
public class GameResource {

    private final String[] actions = {"right", "left", "up", "down", "stop"};
    private final Random random = new Random();

    // DTOs to receive state from Svelte
    public static class GameState {
        public Point player;
        public List<Point> enemies;
        public List<Point> eggs;
        public List<String> valid_moves;
    }
    public static class Point {
        public int x; public int y;
        @Override public String toString() { return "[" + x + "," + y + "]"; }
    }

    @GET
    @Path("/hello")
    public String hello() {
        return "{\"message\": \"Hello World, Quail is here.\"}";
    }

    @POST
    @Blocking
    @Path("/next-move")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public String getNextMove(GameState state) {

        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(2))
                    .build();

            // Create a compact textual prompt with matrix information
            String contextPrompt = String.format(
                "You are a Quail playing a grid maze game. You are at %s. " +
                "Enemies (mouths) to AVOID are at: %s. " +
                "Golden eggs to COLLECT are at: %s. " +
                "Your ONLY valid moves without hitting walls are: %s. " +
                "Pick the best tactical move to get closer to an egg and avoid enemies.",
                state.player, state.enemies, state.eggs, state.valid_moves
            );

            // Build JSON for the local QWEN model
            String jsonPayload = """
                {
                "model": "Qwen/Qwen2.5-1.5B-Instruct-GGUF:Q4_K_M",
                "messages": [
                    {
                        "role": "system",
                        "content": "You are an AI playing a matrix game. Analyze coordinates. Avoid enemies. Get eggs. Reply ONLY with the exact word of the best move."
                    },
                    {
                        "role": "user",
                        "content": "%s"
                    }
                ],
                "max_tokens": 5,
                "temperature": 0.3,
                "grammar": "root ::= (\\"right\\" | \\"left\\" | \\"up\\" | \\"down\\" | \\"stop\\")"
                }
                """.formatted(contextPrompt.replace("\"", "\\\"")); // Safe escape

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://127.0.0.1:8080/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofMillis(1200)) // Tight timeout for real-time games
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            java.net.http.HttpResponse<String> response = client.send(
                    request, java.net.http.HttpResponse.BodyHandlers.ofString());

            String body = response.body();
            int contentIdx = body.indexOf("\"content\":\"") + 11;
            int contentEnd = body.indexOf("\"", contentIdx);
            String raw = body.substring(contentIdx, contentEnd).trim().toLowerCase();

            System.out.println(">>> AI Info: " + state.player + " | Valid moves: " + state.valid_moves);
            
            // Validate the returned move (if it chooses a wall, force a stop or a valid move)
            for (String action : actions) {
                if (raw.contains(action)) {
                    if (state.valid_moves.contains(action)) {
                        System.out.println(">>> Qwen chooses: " + action);
                        return "{\"direction\": \"" + action + "\"}";
                    } else {
                        System.out.println(">>> Qwen chose an illegal move against a wall ("+action+"). Correcting...");
                        // If it chooses a wall, take the first available valid move (or random)
                        String safeMove = state.valid_moves.isEmpty() ? "stop" : state.valid_moves.get(0);
                        return "{\"direction\": \"" + safeMove + "\"}";
                    }
                }
            }

        } catch (Exception e) {
            System.out.println(">>> API/LLM ERROR: " + e.getMessage() + " — sending fallback move");
        }

        // FALLBACK: If it times out or fails to understand, pick a random legal move (“intelligent random pace”)
        if (state != null && state.valid_moves != null && !state.valid_moves.isEmpty()) {
             String randomValidMove = state.valid_moves.get(random.nextInt(state.valid_moves.size()));
             return "{\"direction\": \"" + randomValidMove + "\"}";
        }
        
        return "{\"direction\": \"stop\"}";
    }
}