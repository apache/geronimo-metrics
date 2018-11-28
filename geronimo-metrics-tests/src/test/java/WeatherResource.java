import org.eclipse.microprofile.metrics.annotation.Gauge;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.GET;
import javax.ws.rs.Path;

@Path("weather")
@ApplicationScoped
public class WeatherResource {

    @Gauge(name = "temperature", absolute = true, unit = "celsius",
            displayName = "Weather Temperature",
            description = "This metric shows the temperature.",
            tags = {"weather=temperature"})
    @GET
    @Path("temperature")
    public Integer temperature() {
        return 30;
    }
}