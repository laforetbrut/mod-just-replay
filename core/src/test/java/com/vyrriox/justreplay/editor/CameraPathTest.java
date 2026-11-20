package com.vyrriox.justreplay.editor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Angle wrapping of camera paths: the same results as the game's {@code Mth.wrapDegrees(double)}, and a camera that
 * always turns the short way between two keyframes.
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
}
