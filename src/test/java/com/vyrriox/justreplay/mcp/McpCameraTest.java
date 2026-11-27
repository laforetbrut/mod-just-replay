package com.vyrriox.justreplay.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The camera math against the game's conventions: the look vector of {@code Entity.calculateViewVector}, a vertical
 * field of view, and roll turning the picture about its centre.
 *
 * @author vyrriox
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class McpCameraTest {
    private static final double EPS = 1.0E-9;

    @Test
    void forwardIsTheGamesLookVector() {
        for (double yaw = -360.0; yaw <= 360.0; yaw += 22.5) {
            for (double pitch = -90.0; pitch <= 90.0; pitch += 15.0) {
                for (double roll : new double[]{0.0, 33.0, -90.0}) {
                    double[] f = new McpCamera(0, 0, 0, yaw, pitch, roll, 70, "free").forward();
                    // Entity.calculateViewVector: (sin(-yaw) cos(pitch), -sin(pitch), cos(-yaw) cos(pitch)).
                    double y = Math.toRadians(-yaw);
                    double p = Math.toRadians(pitch);
                    assertEquals(Math.sin(y) * Math.cos(p), f[0], 1.0E-9, "x at " + yaw + "/" + pitch + "/" + roll);
                    assertEquals(-Math.sin(p), f[1], 1.0E-9);
                    assertEquals(Math.cos(y) * Math.cos(p), f[2], 1.0E-9);
                }
            }
        }
    }

    @Test
    void theLookedAtPointIsTheCentre() {
        McpCamera c = new McpCamera(3, 70, -4, 37, 21, 15, 70, "free");
        double[] f = c.forward();
        McpCamera.Projection p = c.project(3 + 10 * f[0], 70 + 10 * f[1], -4 + 10 * f[2], 640, 360);
        assertEquals(320.0, p.screenX(), 1.0E-6);
        assertEquals(180.0, p.screenY(), 1.0E-6);
        assertEquals(10.0, p.depth(), 1.0E-9);
        assertTrue(p.inFront());
        assertTrue(p.onScreen());
    }

    @Test
    void sidesUpAndBehindFollowTheGame() {
        // Facing south (+Z): west (-X) is on the right, east (+X) on the left.
        McpCamera south = new McpCamera(0, 0, 0, 0, 0, 0, 70, "free");
        assertTrue(south.project(-2, 0, 10, 640, 360).screenX() > 320.0);
        assertTrue(south.project(2, 0, 10, 640, 360).screenX() < 320.0);
        assertTrue(south.project(0, 2, 10, 640, 360).screenY() < 180.0);
        // Facing west (yaw 90): north (-Z) is on the right.
        McpCamera west = new McpCamera(0, 0, 0, 90, 0, 0, 70, "free");
        assertTrue(west.project(-10, 0, -2, 640, 360).screenX() > 320.0);
        McpCamera.Projection behind = south.project(0, 0, -10, 640, 360);
        assertFalse(behind.inFront());
        assertFalse(behind.onScreen());
        assertTrue(behind.depth() < 0.0);
        // Looking down, a point below is in front.
        McpCamera down = new McpCamera(0, 10, 0, 0, 90, 0, 70, "free");
        assertTrue(down.project(0, 0, 0, 640, 360).inFront());
    }

    @Test
    void theFieldOfViewIsVerticalAndTheShapeSetsTheWidth() {
        McpCamera c = new McpCamera(0, 0, 0, 0, 0, 0, 90, "free");
        // tan(45 degrees) = 1: at 10 blocks, 10 up is the top edge.
        assertEquals(0.0, c.project(0, 10, 10, 640, 360).screenY(), 1.0E-6);
        assertEquals(360.0, c.project(0, -10, 10, 640, 360).screenY(), 1.0E-6);
        // Half the width is 10 * aspect blocks to the right (west).
        double aspect = 640.0 / 360.0;
        assertEquals(640.0, c.project(-10 * aspect, 0, 10, 640, 360).screenX(), 1.0E-6);
        assertEquals(0.0, c.project(10 * aspect, 0, 10, 640, 360).screenX(), 1.0E-6);
        assertFalse(c.project(-10 * aspect - 1, 0, 10, 640, 360).onScreen());
    }

    @Test
    void rollTurnsThePictureAboutItsCentre() {
        // The game's view is Rz(roll) Rx(pitch) Ry(yaw + 180): with +90 roll, what was on the right is at the top.
        McpCamera rolled = new McpCamera(0, 0, 0, 0, 0, 90, 90, "free");
        McpCamera.Projection p = rolled.project(-5, 0, 10, 640, 360);
        assertEquals(320.0, p.screenX(), 1.0E-6);
        assertTrue(p.screenY() < 180.0, Double.toString(p.screenY()));
        // A full turn changes nothing.
        McpCamera plain = new McpCamera(1, 2, 3, 40, 10, 0, 70, "free");
        McpCamera turned = new McpCamera(1, 2, 3, 400, 10, 360, 70, "free");
        McpCamera.Projection a = plain.project(5, 4, 20, 640, 360);
        McpCamera.Projection b = turned.project(5, 4, 20, 640, 360);
        assertEquals(a.screenX(), b.screenX(), 1.0E-6);
        assertEquals(a.screenY(), b.screenY(), 1.0E-6);
    }

    @Test
    void basisIsOrthonormal() {
        double[][] b = new McpCamera(0, 0, 0, 123, -45, 67, 70, "free").basis();
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double dot = b[i][0] * b[j][0] + b[i][1] * b[j][1] + b[i][2] * b[j][2];
                assertEquals(i == j ? 1.0 : 0.0, dot, EPS);
            }
        }
    }

    @Test
    void fieldOfViewIsKeptSaneAndJsonRounded() {
        assertEquals(McpCamera.MAX_FOV, new McpCamera(0, 0, 0, 0, 0, 0, 500, null).fov());
        assertEquals(70.0, new McpCamera(0, 0, 0, 0, 0, 0, Double.NaN, null).fov());
        assertEquals("free", new McpCamera(0, 0, 0, 0, 0, 0, 70, null).source());
        assertEquals(1.235, new McpCamera(1.23456, 0, 0, 0, 0, 0, 70, "x").toJson().get("x").getAsDouble());
    }
}
