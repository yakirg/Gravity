import javafx.geometry.Point3D;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.awt.geom.Point2D;

@Data
@AllArgsConstructor
public class LaunchStep {
    double end;

    double powerLevel;
    Point3D direction;

}
