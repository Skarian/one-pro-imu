package io.onepro.xr

internal object OneProTrackerSampleMapper {
    fun fromReport(report: OneProReportMessage): OneProImuVectorSample {
        return OneProImuVectorSample(
            gx = report.gx,
            gy = report.gy,
            gz = report.gz,
            ax = report.az,
            ay = report.ay,
            az = report.ax
        )
    }
}
