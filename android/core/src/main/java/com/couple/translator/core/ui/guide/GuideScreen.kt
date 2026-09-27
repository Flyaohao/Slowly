package com.couple.translator.core.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Icecream
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.couple.translator.core.navigation.Screen
import com.couple.translator.core.ui.components.AppBackTopBar
import com.couple.translator.core.ui.components.AppCard
import com.couple.translator.core.ui.components.AppDivider
import com.couple.translator.core.ui.components.pressFeedback
import com.couple.translator.core.ui.theme.AppAccent
import com.couple.translator.core.ui.theme.AppAccentLight
import com.couple.translator.core.ui.theme.AppBackground
import com.couple.translator.core.ui.theme.AppBorderLight
import com.couple.translator.core.ui.theme.AppSurface
import com.couple.translator.core.ui.theme.AppTextPrimary
import com.couple.translator.core.ui.theme.AppTextSecondary
import com.couple.translator.core.ui.theme.AppTextTertiary

/**
 * 使用指南。
 *
 * 定位：**功能地图 + 场景速查**，不是营销页。
 * 解决的问题是"装了 App 也不知道那些功能是干嘛用的"——尤其这个 App
 * 的功能分散在底部栏、抽屉、AI 场景抽屉三处，光看名字猜不出用途。
 *
 * 入口设计（刻意不占底部栏）：
 * 1. 侧边抽屉「使用指南」——抽屉本就是功能总目录，指南是目录的目录，语义最顺；
 * 2. 首次进入自动展示一次（[com.couple.translator.core.data.repository.GuideStore] 记标记）。
 *
 * 本页是**全屏根路由**（`guide`），压在主界面之上，因此不挤占任何 Tab 的空间。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(
    onNavigateBack: () -> Unit,
    onNavigateToRoute: (String) -> Unit,
    isCoupleMode: Boolean = true,
) {
    Scaffold(
        containerColor = AppBackground,
        topBar = {
            AppBackTopBar(
                onBack = onNavigateBack,
                title = "使用指南",
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            GuideHero()

            Spacer(modifier = Modifier.height(28.dp))
            SectionTitle("第一次使用", "三步就能开始")
            Spacer(modifier = Modifier.height(8.dp))
            GuideCard {
                StepRow(
                    index = "1",
                    icon = Icons.Outlined.Quiz,
                    title = "了解自己",
                    desc = "做一份关系问卷，生成 11 个维度的画像与依恋类型。AI 后续的回答会参考它。",
                    route = Screen.QuestionnaireIntro.route,
                    onNavigateToRoute = onNavigateToRoute,
                )
                GuideDivider()
                StepRow(
                    index = "2",
                    icon = Icons.Outlined.Link,
                    title = "绑定伴侣",
                    desc = "一方生成恋爱码，另一方输入即绑定。绑定后自动进入情侣模式。",
                    route = Screen.CoupleBind.route,
                    onNavigateToRoute = onNavigateToRoute,
                )
                GuideDivider()
                StepRow(
                    index = "3",
                    icon = Icons.Outlined.AutoAwesome,
                    title = "开始使用",
                    // L3：底栏入口按模式区分——情侣模式是「军师 + 关系」，
                    // 单身模式是「我 + 观点」，写死两入口会误导另一模式的用户。
                    desc = if (isCoupleMode) {
                        "底部两个入口：军师、关系。核心功能是「军师」，关系状态都在「关系」里。"
                    } else {
                        "底部两个入口：我、观点。核心功能是「我」，观点是给军师的私密记录。"
                    },
                    route = null,
                    onNavigateToRoute = onNavigateToRoute,
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
            // 场景数与下方实际列出的行一致（6 行：日常/听懂 TA/帮我表达/冷静一下/
            // 信件解读/信件改写——双人调解入口 W1 隐藏后不再计入）
            SectionTitle("AI 军师", "整个 App 的核心，共 6 个场景")
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "进入底部「军师」，点左上角可切换场景。每个场景的回答结构和侧重点都不同。",
                style = MaterialTheme.typography.bodySmall,
                color = AppTextTertiary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            GuideCard {
                SceneRow(
                    icon = Icons.Outlined.AutoAwesome,
                    name = "日常",
                    desc = "私密模式。先安抚你的情绪，再结合你的画像给建议。只给你自己看，伴侣看不到。",
                )
                GuideDivider()
                SceneRow(
                    icon = Icons.Outlined.Hearing,
                    name = "听懂 TA",
                    desc = "把伴侣说的一句话丢进去，分析这句话背后真实的诉求。",
                )
                GuideDivider()
                SceneRow(
                    icon = Icons.Outlined.Edit,
                    name = "帮我表达",
                    desc = "你想说的话太冲或太硬，改写成更柔和、对方更容易接受的版本。",
                )
                GuideDivider()
                SceneRow(
                    icon = Icons.Outlined.Icecream,
                    name = "冷静一下",
                    desc = "冷战中。给出破冰的思路和几句可以直接用的开场白。",
                )
                // [W1 隐藏] 双人调解场景说明（调解入口在 P0-3/P0-4 验收前隐藏，避免指向不可达功能）
                // GuideDivider()
                // SceneRow(
                //     icon = Icons.Outlined.People,
                //     name = "双人调解",
                //     desc = "两个人都参与的完整调解流程：各自陈述 → 生成方案 → 双方确认。从军师页面的调解入口进入。",
                // )
                GuideDivider()
                SceneRow(
                    icon = Icons.Outlined.MailOutline,
                    name = "信件解读",
                    desc = "收到一段看不懂的文字，逐句拆解它真正在说什么。",
                )
                GuideDivider()
                SceneRow(
                    icon = Icons.Outlined.Edit,
                    name = "信件改写",
                    desc = "按指定风格重写信件，保留你想表达的核心诉求。入口在信件页内。",
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
            SectionTitle("日常沟通", "写下来，比说出来容易")
            Spacer(modifier = Modifier.height(8.dp))
            GuideCard {
                GuideEntryRow(
                    icon = Icons.Outlined.MailOutline,
                    title = "深度表达",
                    desc = "写信、存草稿、收发往来。可以收藏，也可以让 AI 帮你解读或改写。",
                    route = Screen.LetterList.route,
                    onNavigateToRoute = onNavigateToRoute,
                )
                // [W1 隐藏] 双视角记录跳转项（机制保留，由军师推荐触发）
                // GuideDivider()
                // GuideEntryRow(
                //     icon = Icons.Outlined.FavoriteBorder,
                //     title = "双视角记录",
                //     desc = "同一件事，两个人各自写一份感受，写完互相揭示，看看对方的视角。",
                //     route = Screen.DualPerspectiveList.route,
                //     onNavigateToRoute = onNavigateToRoute,
                // )
            }

            Spacer(modifier = Modifier.height(28.dp))
            SectionTitle("关系沉淀", "把值得留下的都存起来")
            Spacer(modifier = Modifier.height(8.dp))
            GuideCard {
                // [W1 隐藏] 纪念馆跳转项（冻结 10006）
                // GuideEntryRow(
                //     icon = Icons.Outlined.Archive,
                //     title = "纪念馆",
                //     desc = "关系藏品时间线，支持信件、照片、一句话、梗、道歉、承诺、双视角等类别。",
                //     route = Screen.Museum.route,
                //     onNavigateToRoute = onNavigateToRoute,
                // )
                // GuideDivider()
                GuideEntryRow(
                    icon = Icons.Outlined.StarOutline,
                    title = "纪念日",
                    desc = "记下对你们有意义的日子，可以设成每年重复，也可以只算一次。",
                    route = Screen.AnniversaryList.route,
                    onNavigateToRoute = onNavigateToRoute,
                )
                // [W4.3 合并] 关系画像并入「人格画像」（条目在下方「左侧菜单」卡）
                // GuideEntryRow(
                //     icon = Icons.Outlined.ViewSidebar,
                //     title = "关系画像",
                //     desc = "把两个人的画像放在一起看，找出你们容易起冲突的地方。",
                //     route = Screen.CoupleProfile.route,
                //     onNavigateToRoute = onNavigateToRoute,
                // )
            }

            Spacer(modifier = Modifier.height(28.dp))
            SectionTitle("左侧菜单里还有什么", "点左上角图标打开")
            Spacer(modifier = Modifier.height(8.dp))
            GuideCard {
                // [W4.3 合并] 我的画像 + 了解自己 + 关系画像 三入口 → 单一「人格画像」
                // 页内含判断依据与纠正入口；旧路由 ProfileResult/QuestionnaireIntro/CoupleProfile 保留。
                GuideEntryRow(
                    icon = Icons.Outlined.Person,
                    title = "人格画像",
                    desc = "画像、问卷、关系画像三合一，含「判断来自哪里」。",
                    route = Screen.Understanding.route,
                    onNavigateToRoute = onNavigateToRoute,
                )
                // 整改 §8.8：正式「记忆与隐私」入口（此前只能从画像页绕进去）
                GuideDivider()
                GuideEntryRow(
                    icon = Icons.Outlined.Lock,
                    title = "记忆与隐私",
                    desc = "军师记住了什么，逐条可查、可改可见范围、可删除。",
                    route = Screen.Memory.route,
                    onNavigateToRoute = onNavigateToRoute,
                )
                // [W4.3 合并] 了解自己并入上方「人格画像」
                // GuideDivider()
                // GuideEntryRow(
                //     icon = Icons.Outlined.Analytics,
                //     title = "了解自己",
                //     desc = "重新做问卷，或查看历史作答记录。",
                //     route = Screen.QuestionnaireIntro.route,
                //     onNavigateToRoute = onNavigateToRoute,
                // )
                if (isCoupleMode) {
                    GuideDivider()
                    GuideEntryRow(
                        icon = Icons.Outlined.Info,
                        title = "关系管理",
                        desc = "修改在一起的日子和空间设置。解除绑定需要双方同意，并有 72 小时冷静期。",
                        route = Screen.CoupleInfo.route,
                        onNavigateToRoute = onNavigateToRoute,
                    )
                }
                GuideDivider()
                GuideEntryRow(
                    icon = Icons.Outlined.SelfImprovement,
                    title = "设置",
                    desc = "个人信息、通知与企业版本信息。",
                    route = "settings",
                    onNavigateToRoute = onNavigateToRoute,
                )
            }

            if (!isCoupleMode) {
                Spacer(modifier = Modifier.height(28.dp))
                SectionTitle("单身模式", "还没绑定伴侣时可用")
                Spacer(modifier = Modifier.height(8.dp))
                GuideCard {
                    // [W4.5 收缩] 日记降级为「给军师的私密记录」入口，不再宣传笔记软件能力
                    GuideEntryRow(
                        icon = Icons.Outlined.Book,
                        title = "观点",
                        desc = "给军师的私密记录：写下来的心情只有你和军师能看到。",
                        route = Screen.DiaryList.route,
                        onNavigateToRoute = onNavigateToRoute,
                    )
                    // [W1 隐藏] 自我练习跳转项（冻结 10006）
                    // GuideDivider()
                    // GuideEntryRow(
                    //     icon = Icons.Outlined.SelfImprovement,
                    //     title = "自我练习",
                    //     desc = "面向个人的练习题库，做完留下记录。",
                    //     route = Screen.SelfPracticeList.route,
                    //     onNavigateToRoute = onNavigateToRoute,
                    // )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
            SectionTitle("遇到这些情况，该用哪个功能", "按处境查就行")
            Spacer(modifier = Modifier.height(8.dp))
            GuideCard {
                QuickCaseRow("TA 说了句很冲的话，不知道什么意思", "军师 · 听懂 TA")
                GuideDivider()
                QuickCaseRow("心里堵得慌，但不想让任何人知道", "军师 · 日常（私密）")
                GuideDivider()
                QuickCaseRow("有话想说，但怕说出口就伤人", "军师 · 帮我表达")
                GuideDivider()
                // 双人调解入口在 P0-3/P0-4 验收前隐藏，速查文案同步收窄
                QuickCaseRow("吵完架谁都不肯先开口", "军师 · 冷静一下")
                GuideDivider()
                QuickCaseRow("收到一封信，不确定 TA 想表达什么", "信箱 → 打开信件 → 信件解读")
                GuideDivider()
                QuickCaseRow("想道歉，但不知道怎么措辞", "信箱写信时用「信件改写」")
                // [W1 隐藏] 双视角记录跳转项已随上方「日常沟通」卡一并隐藏（速查行不能指向不可达功能）
                // GuideDivider()
                // QuickCaseRow("想让 TA 明白我当时真实的感受", "双视角记录")
                GuideDivider()
                // L3：关系画像入口已并入「人格画像」（W4.3 合并），速查文案同步改指合并后的入口
                QuickCaseRow("为什么我们总在同一件事上吵", "人格画像 + 军师里的 AI 记忆")
                // [W1 隐藏] 关系练习 / 愿望清单均已隐藏，整条速查一并隐藏
                // GuideDivider()
                // QuickCaseRow("想一起做点什么", "关系练习 / 愿望清单")
                // GuideDivider()
                QuickCaseRow("想留住某个重要的瞬间", "纪念日")
            }

            Spacer(modifier = Modifier.height(24.dp))

            TipCard(
                text = "本页随时可以从左上角菜单再次打开。忘记某个功能怎么用，回这里看看就行。"
            )

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

// ============ 页面内部组件 ============

@Composable
private fun GuideHero() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AppAccentLight)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(AppAccent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                    tint = AppBackground,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Slowly慢慢说",
                style = MaterialTheme.typography.titleLarge,
                color = AppTextPrimary,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "把说不出口的话，翻译成对方听得懂的表达。",
            style = MaterialTheme.typography.bodyLarge,
            color = AppTextPrimary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "它不替你谈恋爱，只在你不知道怎么说、怎么理解对方的时候，帮你搭一句话。",
            style = MaterialTheme.typography.bodySmall,
            color = AppTextSecondary,
        )
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.padding(start = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = AppTextPrimary,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AppTextTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun GuideCard(content: @Composable () -> Unit) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        content()
    }
}

@Composable
private fun GuideDivider() {
    AppDivider(modifier = Modifier.padding(start = 60.dp, end = 16.dp))
}

@Composable
private fun StepRow(
    index: String,
    icon: ImageVector,
    title: String,
    desc: String,
    route: String?,
    onNavigateToRoute: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .then(
                if (route != null) {
                    Modifier.pressFeedback(onClick = { onNavigateToRoute(route) })
                } else {
                    Modifier
                }
            )
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(AppAccentLight),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = index,
                style = MaterialTheme.typography.labelLarge,
                color = AppAccent,
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = AppTextSecondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppTextPrimary,
                )
                if (route != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = AppTextTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = AppTextSecondary,
            )
        }
    }
}

@Composable
private fun GuideEntryRow(
    icon: ImageVector,
    title: String,
    desc: String,
    route: String,
    onNavigateToRoute: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .pressFeedback(onClick = { onNavigateToRoute(route) })
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppTextSecondary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(18.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = AppTextPrimary,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = AppTextSecondary,
            )
        }
    }
}

@Composable
private fun SceneRow(
    icon: ImageVector,
    name: String,
    desc: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppAccent,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(18.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = AppTextPrimary,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = AppTextSecondary,
            )
        }
    }
}

@Composable
private fun QuickCaseRow(situation: String, solution: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = situation,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextPrimary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = solution,
            style = MaterialTheme.typography.bodySmall,
            color = AppAccent,
        )
    }
}

@Composable
private fun TipCard(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppSurface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = AppTextTertiary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = AppTextTertiary,
        )
    }
}
