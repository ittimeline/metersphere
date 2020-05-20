package io.metersphere.track.dto;

import io.metersphere.base.domain.TestPlan;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TestPlanDTOWithMetric extends TestPlanDTO {
    private Double passRate;
    private Double testRate;
    private Integer passed;
    private Integer tested;
    private Integer total;
}
