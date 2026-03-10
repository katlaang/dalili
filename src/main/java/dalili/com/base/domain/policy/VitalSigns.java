package dalili.com.base.domain.policy;

public record VitalSigns(
        Integer systolicBp,
        Integer diastolicBp,
        Integer oxygenSaturation,
        Integer heartRate,
        Double temperature,
        Integer respiratoryRate,
        Integer gcs
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Integer systolicBp;
        private Integer diastolicBp;
        private Integer oxygenSaturation;
        private Integer heartRate;
        private Integer respiratoryRate;
        private Integer gcs;
        private Double temperature;

        public Builder systolicBp(Integer val) {
            this.systolicBp = val;
            return this;
        }

        public Builder diastolicBp(Integer val) {
            this.diastolicBp = val;
            return this;
        }

        public Builder oxygenSaturation(Integer val) {
            this.oxygenSaturation = val;
            return this;
        }

        public Builder heartRate(Integer val) {
            this.heartRate = val;
            return this;
        }

        public Builder temperature(Double val) {
            this.temperature = val;
            return this;
        }

        public Builder respiratoryRate(Integer val) {
            this.respiratoryRate = val;
            return this;
        }

        public Builder gcs(Integer val) {
            this.gcs = val;
            return this;
        }

        public VitalSigns build() {
            return new VitalSigns(
                    systolicBp,
                    diastolicBp,
                    oxygenSaturation,
                    heartRate,
                    temperature,
                    respiratoryRate,
                    gcs
            );
        }
    }
}
