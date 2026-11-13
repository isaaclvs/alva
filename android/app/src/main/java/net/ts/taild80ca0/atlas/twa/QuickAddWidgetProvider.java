package net.ts.taild80ca0.atlas.twa;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.os.Bundle;
import net.ts.taild80ca0.atlas.twa.widgets.WidgetRuntime;
import net.ts.taild80ca0.atlas.twa.widgets.WidgetPalette;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public class QuickAddWidgetProvider extends AppWidgetProvider {
  @Override public void onReceive(Context context, Intent intent) {
    super.onReceive(context, intent);
    if (AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(intent.getAction())) {
      WidgetRuntime.refresh(context, goAsync());
    }
  }

  @Override
  public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
    refresh(context);
  }

  @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
    refresh(context);
  }

  /** Render only: shared refresh must not schedule itself recursively. */
  public static void refresh(Context context) {
    AppWidgetManager manager = AppWidgetManager.getInstance(context);
    int[] ids = manager.getAppWidgetIds(new ComponentName(context, QuickAddWidgetProvider.class));
    for (int id : ids) manager.updateAppWidget(id, render(context, id));
  }

  public static RemoteViews render(Context context, int id) {
    WidgetPalette palette = WidgetRuntime.palette(context);
    RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.quick_add_widget);
    views.setInt(R.id.quick_add_widget_background, "setColorFilter", palette.getBackground());
    views.setTextColor(R.id.quick_add_widget_brand, palette.getSoft());
    views.setTextColor(R.id.quick_add_widget_prompt, palette.getInk());
    views.setTextColor(R.id.quick_add_widget_plus, palette.getAccentInk());
    views.setInt(R.id.quick_add_widget_plus_background, "setColorFilter", palette.getAccent());
    Intent intent = new Intent(context, QuickAddActivity.class);
    PendingIntent pending = PendingIntent.getActivity(context, id, intent,
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    views.setOnClickPendingIntent(R.id.quick_add_widget, pending);
    return views;
  }
}
