package net.osmand.plus.notifications;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationCompat.BigTextStyle;
import android.support.v4.app.NotificationCompat.Builder;
import android.view.View;

import net.osmand.plus.NavigationService;
import net.osmand.plus.OsmAndFormatter;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.TargetPointsHelper.TargetPoint;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.plus.routing.RouteCalculationResult.NextDirectionInfo;
import net.osmand.plus.routing.RouteDirectionInfo;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.views.TurnPathHelper;
import net.osmand.plus.views.mapwidgets.NextTurnInfoWidget.TurnDrawable;
import net.osmand.router.TurnType;
import net.osmand.util.Algorithms;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.osmand.plus.NavigationService.USED_BY_NAVIGATION;

public class NavigationNotification extends OsmandNotification {

	public final static String OSMAND_PAUSE_NAVIGATION_SERVICE_ACTION = "OSMAND_PAUSE_NAVIGATION_SERVICE_ACTION";
	public final static String OSMAND_RESUME_NAVIGATION_SERVICE_ACTION = "OSMAND_RESUME_NAVIGATION_SERVICE_ACTION";
	public final static String OSMAND_STOP_NAVIGATION_SERVICE_ACTION = "OSMAND_STOP_NAVIGATION_SERVICE_ACTION";

	private Map<TurnPathHelper.TurnResource, Bitmap> bitmapCache = new HashMap<>();
	private Bitmap turnBitmap;
	private boolean leftSide;

	public NavigationNotification(OsmandApplication app) {
		super(app);
	}

	@Override
	public void init() {
		leftSide = app.getSettings().DRIVING_REGION.get().leftHandDriving;
		app.registerReceiver(new BroadcastReceiver() {

			@Override
			public void onReceive(Context context, Intent intent) {
				RoutingHelper routingHelper = app.getRoutingHelper();
				routingHelper.setRoutePlanningMode(true);
				routingHelper.setFollowingMode(false);
				routingHelper.setPauseNaviation(true);
			}
		}, new IntentFilter(OSMAND_PAUSE_NAVIGATION_SERVICE_ACTION));

		app.registerReceiver(new BroadcastReceiver() {

			@Override
			public void onReceive(Context context, Intent intent) {
				RoutingHelper routingHelper = app.getRoutingHelper();
				routingHelper.setRoutePlanningMode(false);
				routingHelper.setFollowingMode(true);
			}
		}, new IntentFilter(OSMAND_RESUME_NAVIGATION_SERVICE_ACTION));

		app.registerReceiver(new BroadcastReceiver() {

			@Override
			public void onReceive(Context context, Intent intent) {
				app.stopNavigation();
			}
		}, new IntentFilter(OSMAND_STOP_NAVIGATION_SERVICE_ACTION));
	}

	@Override
	public NotificationType getType() {
		return NotificationType.NAVIGATION;
	}

	@Override
	public int getPriority() {
		return NotificationCompat.PRIORITY_HIGH;
	}

	@Override
	public boolean isActive() {
		return isEnabled();
	}

	@Override
	public boolean isEnabled() {
		NavigationService service = app.getNavigationService();
		return service != null && (service.getUsedBy() & USED_BY_NAVIGATION) != 0;
	}

	@Override
	public Builder buildNotification() {
		NavigationService service = app.getNavigationService();
		String notificationTitle;
		StringBuilder notificationText = new StringBuilder();
		color = 0;
		icon = R.drawable.ic_action_start_navigation;
		turnBitmap = null;
		RoutingHelper routingHelper = app.getRoutingHelper();
		boolean followingMode = routingHelper.isFollowingMode() || app.getLocationProvider().getLocationSimulation().isRouteAnimating();
		if (service != null && (service.getUsedBy() & USED_BY_NAVIGATION) != 0) {
			color = app.getResources().getColor(R.color.osmand_orange);

			String distanceStr = app.getString(R.string.route_distance) + OsmAndFormatter.getFormattedDistance(app.getRoutingHelper().getLeftDistance(), app);
			String durationStr = app.getString(R.string.access_arrival_time) + ": " + SimpleDateFormat.getTimeInstance(DateFormat.SHORT)
					.format(new Date(System.currentTimeMillis() + app.getRoutingHelper().getLeftTime() * 1000));

			TurnType turnType = null;
			boolean deviatedFromRoute;
			int turnImminent = 0;
			int nextTurnDistance = 0;
			RouteDirectionInfo ri = null;
			if (routingHelper.isRouteCalculated() && followingMode) {
				deviatedFromRoute = routingHelper.isDeviatedFromRoute();

				if (deviatedFromRoute) {
					turnImminent = 0;
					turnType = TurnType.valueOf(TurnType.OFFR, leftSide);
					nextTurnDistance = (int) routingHelper.getRouteDeviation();
				} else {
					NextDirectionInfo r = routingHelper.getNextRouteDirectionInfo(new NextDirectionInfo(), true);
					if (r != null && r.distanceTo > 0 && r.directionInfo != null) {
						ri = r.directionInfo;
						turnType = r.directionInfo.getTurnType();
						nextTurnDistance = r.distanceTo;
						turnImminent = r.imminent;
					}
				}

				if (turnType != null) {
					TurnDrawable drawable = new TurnDrawable(app, false);
					int height = (int) app.getResources().getDimension(android.R.dimen.notification_large_icon_height);
					int width = (int) app.getResources().getDimension(android.R.dimen.notification_large_icon_width);
					drawable.setBounds(0, 0, width, height);
					drawable.setTurnType(turnType);
					drawable.setTurnImminent(turnImminent, deviatedFromRoute);
					turnBitmap = drawableToBitmap(drawable);
				}

				notificationTitle = OsmAndFormatter.getFormattedDistance(nextTurnDistance, app)
						+ (turnType != null ? " • " + RouteCalculationResult.toString(turnType, app) : "");
				if (ri != null && !Algorithms.isEmpty(ri.getDescriptionRoutePart())) {
					notificationText.append(ri.getDescriptionRoutePart());
					notificationText.append("\n");
				}

				int distanceToNextIntermediate = routingHelper.getLeftDistanceNextIntermediate();
				if (distanceToNextIntermediate > 0) {
					int nextIntermediateIndex = routingHelper.getRoute().getNextIntermediate();
					List<TargetPoint> intermediatePoints = app.getTargetPointsHelper().getIntermediatePoints();
					if (nextIntermediateIndex < intermediatePoints.size()) {
						TargetPoint nextIntermediate = intermediatePoints.get(nextIntermediateIndex);
						notificationText.append(OsmAndFormatter.getFormattedDistance(distanceToNextIntermediate, app))
								.append(" • ")
								.append(nextIntermediate.getOnlyName());
						notificationText.append("\n");
					}
				}

				notificationText.append(distanceStr).append(" • ").append(durationStr);

			} else {
				notificationTitle = app.getString(R.string.shared_string_navigation);
				String error = routingHelper.getLastRouteCalcErrorShort();
				if (Algorithms.isEmpty(error)) {
					notificationText.append("Route calculation...");
				} else {
					notificationText.append(error);
				}
			}

		} else {
			return null;
		}

		final Builder notificationBuilder = createBuilder()
				.setContentTitle(notificationTitle)
				.setStyle(new BigTextStyle().bigText(notificationText))
				.setLargeIcon(turnBitmap);

		Intent stopIntent = new Intent(OSMAND_STOP_NAVIGATION_SERVICE_ACTION);
		PendingIntent stopPendingIntent = PendingIntent.getBroadcast(app, 0, stopIntent,
				PendingIntent.FLAG_UPDATE_CURRENT);
		notificationBuilder.addAction(R.drawable.ic_action_remove_dark,
				app.getString(R.string.shared_string_control_stop), stopPendingIntent);

		if (routingHelper.isRouteCalculated() && followingMode) {
			Intent pauseIntent = new Intent(OSMAND_PAUSE_NAVIGATION_SERVICE_ACTION);
			PendingIntent pausePendingIntent = PendingIntent.getBroadcast(app, 0, pauseIntent,
					PendingIntent.FLAG_UPDATE_CURRENT);
			notificationBuilder.addAction(R.drawable.ic_pause,
					app.getString(R.string.shared_string_pause), pausePendingIntent);
		} else {
			Intent resumeIntent = new Intent(OSMAND_STOP_NAVIGATION_SERVICE_ACTION);
			PendingIntent resumePendingIntent = PendingIntent.getBroadcast(app, 0, resumeIntent,
					PendingIntent.FLAG_UPDATE_CURRENT);
			notificationBuilder.addAction(R.drawable.ic_play_dark,
					app.getString(R.string.shared_string_continue), resumePendingIntent);
		}

		return notificationBuilder;
	}

	@Override
	public void setupNotification(Notification notification) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			int smallIconViewId = app.getResources().getIdentifier("right_icon", "id", android.R.class.getPackage().getName());

			if (smallIconViewId != 0) {
				if (notification.contentIntent != null)
					notification.contentView.setViewVisibility(smallIconViewId, View.INVISIBLE);

				if (notification.headsUpContentView != null)
					notification.headsUpContentView.setViewVisibility(smallIconViewId, View.INVISIBLE);

				if (notification.bigContentView != null)
					notification.bigContentView.setViewVisibility(smallIconViewId, View.INVISIBLE);
			}
		}
	}

	public Bitmap drawableToBitmap(Drawable drawable) {
		int height = (int) app.getResources().getDimension(android.R.dimen.notification_large_icon_height);
		int width = (int) app.getResources().getDimension(android.R.dimen.notification_large_icon_width);

		Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(bitmap);
		canvas.drawColor(0, PorterDuff.Mode.CLEAR);
		drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
		drawable.draw(canvas);

		return bitmap;
	}

	@Override
	public int getUniqueId() {
		return NAVIGATION_NOTIFICATION_SERVICE_ID;
	}
}