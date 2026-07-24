import android.app.usage.UsageEvents;
public class Test {
    public static void main(String[] args) {
        System.out.println("MOVE_TO_FOREGROUND: " + UsageEvents.Event.MOVE_TO_FOREGROUND);
        System.out.println("ACTIVITY_RESUMED: " + UsageEvents.Event.ACTIVITY_RESUMED);
    }
}
