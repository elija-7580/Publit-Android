<!--
SPDX-License-Identifier: MIT
SPDX-FileCopyrightText: 2025 AgentLand Contributors (role texts); 2026 Elija Samuel Friedrich-Ulrich (selection)
-->

# Rollen-Agenten (agency-agents)

`.claude/agents/` enthält 50 ausgewählte Rollen aus
[msitarzewski/agency-agents](https://github.com/msitarzewski/agency-agents),
fest auf Commit `8ae6164602c95ca78828b148b9052460ee426885` (8.11.2026), MIT-Lizenz
(siehe `LICENSE-agency-agents`). Nur der Frontmatter wurde für Claude Code angepasst
(`name` als Kurzname, ungültige Farben entfernt); jede Datei trägt einen Quellkommentar.

**Nutzen:** „Nimm den reality-checker und prüf, ob das Feature fertig ist.“ In Sessions,
die im Repo-Ordner starten, sind die Rollen direkt als Subagent-Typen da. Sonst die
Datei lesen und ihren Text als Prompt für einen Agent verwenden.

**Weitere Rollen** (alle ~280) per Account-Skill `agency-rollen` oder direkt von GitHub
am selben Commit holen. Neue Rollen hier nur geprüft und mit Quellkommentar aufnehmen.

| Kurzname | Rolle | Wofür |
|---|---|---|
| `mobile-app-builder` | Mobile App Builder | Specialized mobile application developer with expertise in native iOS/Android development and cross-platform frameworks |
| `mobile-release-engineer` | Mobile Release Engineer | Expert mobile release and distribution engineer for iOS and Android — code signing, provisioning, fastlane pipelines, App Store Connect and Play Console submission, phased rollouts, and crash-triaged release health. |
| `software-architect` | Software Architect | Expert software architect specializing in system design, domain-driven design, architectural patterns, and technical decision-making for scalable, maintainable systems. |
| `code-reviewer` | Code Reviewer | Expert code reviewer who provides constructive, actionable feedback focused on correctness, maintainability, security, and performance — not style preferences. |
| `minimal-change-engineer` | Minimal Change Engineer | Engineering specialist focused on minimum-viable diffs — fixes only what was asked, refuses scope creep, prefers three similar lines over a premature abstraction. The discipline that prevents bug-fix PRs from becoming refactor avalanches. |
| `devops-automator` | DevOps Automator | Expert DevOps engineer specializing in infrastructure automation, CI/CD pipeline development, and cloud operations |
| `sre` | SRE (Site Reliability Engineer) | Expert site reliability engineer specializing in SLOs, error budgets, observability, chaos engineering, and toil reduction for production systems at scale. |
| `frontend-developer` | Frontend Developer | Expert frontend developer specializing in modern web technologies, React/Vue/Angular frameworks, UI implementation, and performance optimization |
| `backend-architect` | Backend Architect | Senior backend architect specializing in scalable system design, database architecture, API development, and cloud infrastructure. Builds robust, secure, performant server-side applications and microservices |
| `i18n-engineer` | Internationalization Engineer | Expert i18n engineer for ICU MessageFormat, CLDR plural rules, RTL and bidirectional layouts, locale-aware date/number/currency formatting, string extraction pipelines, and pseudo-localization testing. |
| `privacy-engineer` | Privacy Engineer | Expert privacy engineer who implements privacy in code — PII discovery and classification, data minimization, consent enforcement at the API layer, automated DSAR and deletion across services, pseudonymization/tokenization, and retention automation. Builds the technical controls a privacy policy only promises. |
| `technical-writer` | Technical Writer | Expert technical writer specializing in developer documentation, API references, README files, and tutorials. Transforms complex engineering concepts into clear, accurate, and engaging docs that developers actually read and use. |
| `payments-billing-engineer` | Payments & Billing Engineer | Expert payments engineer for PSP integrations (Stripe, Adyen, Braintree, PayPal), idempotent payment flows, webhook processing, subscription billing, SCA/3DS, PCI scope reduction, and financial reconciliation. |
| `appsec-engineer` | Application Security Engineer | AppSec specialist who secures the software development lifecycle through threat modeling, secure code review, SAST/DAST integration, and developer security education that makes secure code the default. |
| `ai-generated-code-auditor` | AI-Generated Code Security Auditor | Security reviewer for AI-generated and vibe-coded apps — hunts the hardcoded secrets, broken row-level security, and prompt-injection sinks that coding assistants ship by default, then drives a scan, fix, and rescan loop with honest, CWE-mapped findings. |
| `secrets-credential-engineer` | Secrets & Credential Hygiene Engineer | Owns the full lifecycle of secrets and credentials — detection, prevention, vaulting, rotation, and leak response — so an application runs on short-lived, least-privilege credentials that are never in the code and are already rotated by the time a leak is found. |
| `accessibility-auditor` | Accessibility Auditor | Expert accessibility specialist who audits interfaces against WCAG standards, tests with assistive technologies, and ensures inclusive design. Defaults to finding barriers — if it's not tested with a screen reader, it's not accessible. |
| `reality-checker` | Reality Checker | Stops fantasy approvals, evidence-based certification - Default to "NEEDS WORK", requires overwhelming proof for production readiness |
| `performance-benchmarker` | Performance Benchmarker | Expert performance testing and optimization specialist focused on measuring, analyzing, and improving system performance across all applications and infrastructure |
| `api-tester` | API Tester | Expert API testing specialist focused on comprehensive API validation, performance testing, and quality assurance across all systems and third-party integrations |
| `ui-designer` | UI Designer | Expert UI designer specializing in visual design systems, component libraries, and pixel-perfect interface creation. Creates beautiful, consistent, accessible user interfaces that enhance UX and reflect brand identity |
| `ux-architect` | UX Architect | Technical architecture and UX specialist who provides developers with solid foundations, CSS systems, and clear implementation guidance |
| `ux-researcher` | UX Researcher | Expert user experience researcher specializing in user behavior analysis, usability testing, and data-driven design insights. Provides actionable research findings that improve product usability and user satisfaction |
| `brand-guardian` | Brand Guardian | Expert brand strategist and guardian specializing in brand identity development, consistency maintenance, and strategic brand positioning |
| `visual-storyteller` | Visual Storyteller | Expert visual communication specialist focused on creating compelling visual narratives, multimedia content, and brand storytelling through design. Specializes in transforming complex information into engaging visual stories that connect with audiences and drive emotional engagement. |
| `app-store-optimizer` | App Store Optimizer | Expert app store marketing specialist focused on App Store Optimization (ASO), conversion rate optimization, and app discoverability |
| `growth-hacker` | Growth Hacker | Expert growth strategist specializing in rapid user acquisition through data-driven experimentation. Develops viral loops, optimizes conversion funnels, and finds scalable growth channels for exponential business growth. |
| `reddit-community-builder` | Reddit Community Builder | Expert Reddit marketing specialist focused on authentic community engagement, value-driven content creation, and long-term relationship building. Masters Reddit culture navigation. |
| `linkedin-content-creator` | LinkedIn Content Creator | Expert LinkedIn content strategist focused on thought leadership, personal brand building, and high-engagement professional content. Masters LinkedIn's algorithm and culture to drive inbound opportunities for founders, job seekers, developers, and anyone building a professional presence. |
| `seo-specialist` | SEO Specialist | Expert search engine optimization strategist specializing in technical SEO, content optimization, link authority building, and organic search growth. Drives sustainable traffic through data-driven search strategies. |
| `ai-citation-strategist` | AI Citation Strategist | Expert in AI recommendation engine optimization (AEO/GEO) — audits brand visibility across ChatGPT, Claude, Gemini, and Perplexity, identifies why competitors get cited instead, and delivers content fixes that improve AI citations |
| `pr-communications-manager` | PR & Communications Manager | Strategic public relations and communications specialist for media relations, press releases, crisis communications, executive thought leadership, brand reputation management, and integrated communications planning — building and protecting reputations through earned media, storytelling, and proactive narrative control |
| `content-creator` | Content Creator | Expert content strategist and creator for multi-platform campaigns. Develops editorial calendars, creates compelling copy, manages brand storytelling, and optimizes content for engagement across all digital channels. |
| `developer-community-builder` | Developer Community Builder | Grows and sustains developer communities — Discord servers, GitHub discussions, forums, and contributor programs — turning users into advocates and advocates into contributors. |
| `product-manager` | Product Manager | Holistic product leader who owns the full product lifecycle — from discovery and strategy through roadmap, stakeholder alignment, go-to-market, and outcome measurement. Bridges business goals, user needs, and technical reality to ship the right thing at the right time. |
| `feedback-synthesizer` | Feedback Synthesizer | Expert in collecting, analyzing, and synthesizing user feedback from multiple channels to extract actionable product insights. Transforms qualitative feedback into quantitative priorities and strategic recommendations. |
| `sprint-prioritizer` | Sprint Prioritizer | Expert product manager specializing in agile sprint planning, feature prioritization, and resource allocation. Focused on maximizing team velocity and business value delivery through data-driven prioritization frameworks. |
| `trend-researcher` | Trend Researcher | Expert market intelligence analyst specializing in identifying emerging trends, competitive analysis, and opportunity assessment. Focused on providing actionable insights that drive product strategy and innovation decisions. |
| `outbound-strategist` | Outbound Strategist | Signal-based outbound specialist who designs multi-channel prospecting sequences, defines ICPs, and builds pipeline through research-driven personalization — not volume. |
| `proposal-strategist` | Proposal Strategist | Strategic proposal architect who transforms RFPs and sales opportunities into compelling win narratives. Specializes in win theme development, competitive positioning, executive summary craft, and building proposals that persuade rather than merely comply. |
| `deal-strategist` | Deal Strategist | Senior deal strategist specializing in MEDDPICC qualification, competitive positioning, and win planning for complex B2B sales cycles. Scores opportunities, exposes pipeline risk, and builds deal strategies that survive forecast review. |
| `discovery-coach` | Discovery Coach | Coaches sales teams on elite discovery methodology — question design, current-state mapping, gap quantification, and call structure that surfaces real buying motivation. |
| `grant-writer` | Grant Writer | Expert grant writing specialist for nonprofits, research institutions, and social enterprises — covering prospect research, letter of inquiry writing, full proposal development, budget narratives, federal and foundation grants, and post-award reporting to maximize funding success |
| `business-strategist` | Business Strategist | Senior management consulting specialist for competitive analysis, market entry strategy, business model design, growth planning, organizational strategy, and strategic decision-making — translating complex market dynamics into clear, actionable strategies that create sustainable competitive advantage |
| `pricing-analyst` | Pricing Analyst | Specialized pricing analyst who develops optimal pricing models through market research, competitor analysis, cost structure evaluation, and margin optimization — turning pricing from guesswork into a data-driven competitive advantage. |
| `chief-of-staff` | Chief of Staff | Master coordinator for founders and executives — filters noise, owns processes, enforces consistency, routes decisions, and positions outputs for impact so the boss can think clearly. |
| `developer-advocate` | Developer Advocate | Expert developer advocate specializing in building developer communities, creating compelling technical content, optimizing developer experience (DX), and driving platform adoption through authentic engineering engagement. Bridges product and engineering teams with external developers. |
| `data-privacy-officer` | Data Privacy Officer | Corporate data privacy specialist and DPO who builds GDPR, CCPA, and global privacy compliance programs — covering data mapping, privacy impact assessments, consent management, breach response, vendor due diligence, and regulatory engagement. |
| `language-translator` | Language Translator | Real-time Spanish ↔ English translation specialist with cultural context, regional dialect awareness, travel phrase guidance, and tone-appropriate communication for everyday, business, and emergency situations |
| `legal-compliance-checker` | Legal Compliance Checker | Expert legal and compliance specialist ensuring business operations, data handling, and content creation comply with relevant laws, regulations, and industry standards across multiple jurisdictions. |
