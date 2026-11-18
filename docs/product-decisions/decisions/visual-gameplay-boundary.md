# visual-gameplay-boundary

- Requirement: [REQ.render-model-consistently](../requirements/req-render-model-consistently.md#reqrender-model-consistently)
- Select: 模型体型、碰撞与非视觉玩法边界
- Needs:
  - 涉及外部模组状态与呈现: [BC.integration-does-not-redefine-gameplay](mod-integration.md#bcintegration-does-not-redefine-gameplay)
  - 高风险侵入与例外: [BC.intrusion-exception-is-specific](integration-risk.md#bcintrusion-exception-is-specific)
  - 确定授权范围: [CON.visual-only-scope](../shared.md#convisual-only-scope)
- Landing:
  - [concept](../../concepts/rendering.md#设计目标)
  - [architecture](../../architecture/integration/tacz-hit-detection.md)
  - [status](../../status/known-issues/animation.md#tacz-player-projectile-hit-detection)

## DD.rendering-only-replacement

- Constraints: [CON.visual-only-scope](../shared.md#convisual-only-scope)
- Claim: 默认情况下，模型与动画系统的替换以视觉表现为目标，不以外观输入重新定义玩法规则。仅允许本包明确记录的 TaCZ 玩家弹道命中专项例外；该例外不得推导为通用模型物理、其他武器碰撞或其他玩法规则。
- Rationale: 系统替换以更丰富的模型效果为价值，并已接受依赖旧系统的相关内容会被破坏；这种兼容代价不能进一步成为按模型体型、动作或外观改变碰撞与战斗规则的理由。对 TaCZ 玩家弹道的专项例外只在服务端可以完整验证模型几何并从可信游戏状态确定性重建姿态时启用，才能让模型特定的枪弹命中与玩家所见模型一致；将适用集限制在可证明的服务端姿态，并在任何输入、几何、姿态或运算不完整时保留 TaCZ 原 AABB 路径，避免把客户端绘制结果或不完整区域提升为战斗事实。

### BC.visual-size-does-not-change-physics

- Claim: 除 [BC.tacz-player-bullet-model-hitbox](#bctacz-player-bullet-model-hitbox) 明确限定的 TaCZ 玩家弹道命中窄相外，模型即使视觉上大于或小于实体，碰撞箱仍遵循原版逻辑，攻击距离、跳跃高度、武器伤害等非视觉玩法参数不因模型或动画改变。

### BC.tacz-player-bullet-model-hitbox

- Claim: 仅 TaCZ 子弹对玩家的命中窄相可使用由服务端验证的完整模型几何与服务端可确定性重建的姿态生成的 OBB；原版玩家物理 bounding box、移动与阻挡逻辑保持不变。姿态或模型选择不得依赖客户端上传的骨骼矩阵或 hitbox。模型/姿态不受支持、缺少必要区域、数据或变换缺失/越界/非有限、计算失败或历史样本不可用时，整组 OBB 不可用并沿用 TaCZ 原 AABB 命中路径。此例外不适用于 TaCZ 非玩家目标、近战或爆炸处理、其他模组的弹药/武器、其他实体或通用物理；headshot 只由同一历史时刻实际命中的头部 OBB 区域决定。
