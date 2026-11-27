package com.vyrriox.justreplay.editor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Angle wrapping of camera paths: the same results as the game's {@code Mth.wrapDegrees(double)}, and a camera that
 * always turns the short way between two keyframes. The path of a camera is built once and kept while nothing it is
 * built from changes, and a kept path samples exactly like a new one.
 *
 * @author vyrriox
 */
class CameraPathTest {
    private static final double EPS = 1.0E-9;

    @Test
    void anglesWrapIntoMinus180To180() {
        assertEquals(0.0, CameraPath.wrapDegrees(0.0));
        assertEquals(179.5, CameraPath.wrapDegrees(179.5));
        assertEquals(-180.0, CameraPath.wrapDegrees(180.0));
        assertEquals(-180.0, CameraPath.wrapDegrees(-180.0));
        assertEquals(-170.0, CameraPath.wrapDegrees(190.0));
        assertEquals(170.0, CameraPath.wrapDegrees(-190.0));
        assertEquals(0.0, CameraPath.wrapDegrees(360.0));
        assertEquals(-180.0, CameraPath.wrapDegrees(540.0));
        assertEquals(-180.0, CameraPath.wrapDegrees(-540.0));
        assertEquals(1.0, CameraPath.wrapDegrees(-359.0));
        assertEquals(45.0, CameraPath.wrapDegrees(7245.0));
    }

    @Test
    void edgeValuesBehaveLikeTheGame() {
        // Same bits as Mth.wrapDegrees: the sign of zero survives, NaN and infinities give NaN.
        assertEquals(Double.doubleToLongBits(-0.0), Double.doubleToLongBits(CameraPath.wrapDegrees(-0.0)));
        assertEquals(Double.doubleToLongBits(-0.0), Double.doubleToLongBits(CameraPath.wrapDegrees(-360.0)));
        assertTrue(Double.isNaN(CameraPath.wrapDegrees(Double.NaN)));
        assertTrue(Double.isNaN(CameraPath.wrapDegrees(Double.POSITIVE_INFINITY)));
        assertTrue(Double.isNaN(CameraPath.wrapDegrees(Double.NEGATIVE_INFINITY)));
        assertEquals(Double.MIN_VALUE, CameraPath.wrapDegrees(Double.MIN_VALUE));
        double wrapped = CameraPath.wrapDegrees(Math.nextDown(-180.0));
        assertTrue(wrapped > 179.0 && wrapped < 180.0, "just under -180 lands just under 180: " + wrapped);
    }

    @Test
    void theCameraTurnsTheShortWay() {
        // 170 to -170 is 20 degrees through 180, not 340 through 0.
        CameraPath path = new CameraPath(List.of(
                new ReplayProject.CameraKeyframe(0, 0, 64, 0, 170.0F, 0, 0, 70),
                new ReplayProject.CameraKeyframe(20, 10, 64, 0, -170.0F, 0, 0, 70)),
                CameraPath.Curve.LINEAR, CameraPath.Timing.KEYFRAMES, false);
        assertEquals(180.0, path.sample(10)[3], EPS);
        assertEquals(190.0, path.sample(20)[3], EPS);
        assertEquals(5.0, path.sample(10)[0], EPS);
    }

    @Test
    void rollIsUnwrappedTooAndAFullTurnStaysPut() {
        CameraPath path = new CameraPath(List.of(
                new ReplayProject.CameraKeyframe(0, 0, 64, 0, 10.0F, 0, -175.0F, 70),
                new ReplayProject.CameraKeyframe(10, 0, 64, 0, 370.0F, 0, 175.0F, 70)),
                CameraPath.Curve.LINEAR, CameraPath.Timing.KEYFRAMES, false);
        assertEquals(10.0, path.sample(5)[3], EPS);
        assertEquals(-180.0, path.sample(5)[5], EPS);
        assertEquals(-185.0, path.sample(10)[5], EPS);
    }

    private static ReplayProject.Camera camera() {
        ReplayProject.Camera camera = new ReplayProject.Camera();
        camera.curve = "BEZIER";
        for (int i = 0; i < 6; i++) {
            camera.keyframes.add(new ReplayProject.CameraKeyframe(i * 40, i * 3.5, 70 + i, -i * 2.25, i * 50.0F, 10.0F - i, i, 70.0F + i));
        }
        return camera;
    }

    private static CameraPath fresh(ReplayProject.Camera c) {
        return new CameraPath(c.keyframes, CameraPath.parse(CameraPath.Curve.class, c.curve, CameraPath.Curve.CATMULL_ROM),
                CameraPath.parse(CameraPath.Timing.class, c.timing, CameraPath.Timing.CONSTANT_SPEED), c.ease);
    }

    private static void samplesLikeANewPath(ReplayProject.Camera c) {
        CameraPath kept = CameraPath.of(c);
        CameraPath made = fresh(c);
        for (double t = -5; t <= 210; t += 0.37) {
            assertArrayEquals(made.sample(t), kept.sample(t), "tick " + t);
        }
    }

    @Test
    void theSamePathServesEveryFrameWhileNothingChanges() {
        ReplayProject.Camera c = camera();
        CameraPath first = CameraPath.of(c);
        assertSame(first, CameraPath.of(c));
        c.keyframes.set(2, new ReplayProject.CameraKeyframe(80, 7.0, 72, -4.5, 100.0F, 8.0F, 2, 72.0F));
        assertSame(first, CameraPath.of(c), "an equal keyframe object changes nothing");
        samplesLikeANewPath(c);
    }

    @Test
    void anyChangeBuildsTheRightPathAgain() {
        ReplayProject.Camera c = camera();
        java.util.List<Runnable> changes = List.of(
                () -> c.keyframes.get(1).x += 0.5,
                () -> c.keyframes.get(2).y -= 1.0,
                () -> c.keyframes.get(3).z = -0.0,
                () -> c.keyframes.get(4).yaw += 360.0F,
                () -> c.keyframes.get(0).pitch = -30.0F,
                () -> c.keyframes.get(5).roll = 15.0F,
                () -> c.keyframes.get(1).fov = 30.0F,
                () -> c.keyframes.get(3).tick += 7,
                () -> c.keyframes.add(new ReplayProject.CameraKeyframe(260, 0, 64, 0, 0, 0, 0, 70)),
                () -> c.keyframes.remove(0),
                () -> c.curve = "CATMULL_ROM",
                () -> c.curve = "LINEAR",
                () -> c.timing = "KEYFRAMES",
                () -> c.ease = !c.ease,
                () -> c.curve = "not a curve");
        for (Runnable change : changes) {
            CameraPath before = CameraPath.of(c);
            change.run();
            assertNotSame(before, CameraPath.of(c));
            samplesLikeANewPath(c);
        }
    }

    @Test
    void theKeptPathIsNeverSaved() {
        ReplayProject.Camera c = camera();
        CameraPath.of(c);
        String json = new com.google.gson.Gson().toJson(c);
        assertTrue(!json.contains("builtPath") && !json.contains("arcLength"), json);
    }
}
