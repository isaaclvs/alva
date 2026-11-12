package net.ts.taild80ca0.atlas.twa;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public class QuickAddWidgetProvider extends AppWidgetProvider {
  @Override
  public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
    for (int id : ids) {
      RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.quick_add_widget);
      Intent intent = new Intent(context, QuickPanelActivity.class);
      PendingIntent pending = PendingIntent.getActivity(context, id, intent,
          PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      views.setOnClickPendingIntent(R.id.quick_add_widget, pending);
      manager.updateAppWidget(id, views);
    }
  }
}
