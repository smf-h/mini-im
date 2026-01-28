# Summary

| Experiment | 假设 | Change（commit/文件） | p99 Δ | error Δ | 吞吐 Δ | CPU/GC Δ | 一致性（重复/乱序/丢） | 成本/风险 | 结论（保留/回滚） |
|---|---|---|---:|---:|---:|---:|---|---|---|
<!-- ROWS -->

## 总结段（必须回答的 5 句）
1) 本轮（或本次优化周期）最终是否达标：{{OverallPass}}；依据：{{OverallEvidence}}
2) 最大贡献改动：{{BestExperiment}}，带来的关键收益：{{BestBenefit}}，代价：{{BestCost}}
3) 最不值得改动：{{WorstExperiment}}，原因：{{WorstReason}}
4) 退化点与原因：{{Regression}}；采取的处理：{{RegressionHandling}}
5) 当前最可信的瓶颈假设与下一步：{{NextHypothesis}}；下一轮实验建议：{{NextAction}}

## Appendix
- Plan: {{PlanRef}}
- Runner: {{RunnerRef}}

