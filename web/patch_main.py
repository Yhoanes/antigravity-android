#!/usr/bin/env python3
"""
web/patch_main.py - Dynamic patcher for web/main.js (Antigravity v2.33.0 Android UI).

Applies:
1. Android Bridge theme sync hooks (window.Android.getTheme / setTheme / setThemeByUser).
2. Original PC AuxPane v2 redesign (Search tabs/files/plugins/subagents/artifacts/tasks)
   and the header Overview floating popover button ([📄] OverviewToggleIconButton) on Mobile & Desktop.
3. Original PC Customizations (Smart Marketplace V3 | Installed) and Automations inside Settings (⚙️).
4. Voice Mode, Microphone, Permissions Selector badge, /sdcard default path, and touch/menu fixes.
5. Global Root ErrorBoundary (__AgRootEB) above SettingsModal & Router.
"""

import os
import sys


def must_replace(content, old, new, label):
    cnt = content.count(old)
    if cnt != 1:
        raise RuntimeError(f"[web-patch-2.33.0] expected count=1 for {label}, got {cnt}")
    print(f"  [OK] {label}")
    return content.replace(old, new, 1)


LOADER_INIT_V3 = (
    'typeof window!=="undefined"&&!window.__agMpSmartV3&&(window.__agMpSmartV3=!0,window.__agLoadMarketplaceCatalog=()=>{'
    'if(window.__agMpCatalog)return Promise.resolve(window.__agMpCatalog);'
    'if(window.__agMpCatalogPromise)return window.__agMpCatalogPromise;'
    'window.__agMpCatalogPromise=(async()=>{'
    'var base={featured:[],search:{customizations:[],groups:[]},details:{}};'
    'try{var r=await fetch("/marketplace_catalog.json");if(r.ok)base=await r.json()}catch(_e){}'
    'try{var ac=typeof AbortController!=="undefined"?new AbortController:null,tid=ac?setTimeout(()=>ac.abort(),3500):null,'
    'r2=await fetch("https://raw.githubusercontent.com/Yhoanes/antigravity-android-plugins/main/catalog.json",ac?{signal:ac.signal}:void 0);'
    'tid&&clearTimeout(tid);'
    'if(r2.ok){var rem=await r2.json();if(rem&&typeof rem==="object"){'
    'var rc=(rem.search&&rem.search.customizations)||[],bc=(base.search&&base.search.customizations)||[],rIds=new Set(rc.map(x=>x.id)),'
    'mc=rc.map(x=>Object.assign({_androidNative:!0},x)).concat(bc.filter(x=>!rIds.has(x.id))),'
    'rg2=(rem.search&&rem.search.groups)||[],bg=(base.search&&base.search.groups)||[],rgIds=new Set(rg2.map(x=>x.id)),'
    'mg=rg2.concat(bg.filter(x=>!rgIds.has(x.id))),'
    'bf=(base.featured||[]).filter(x=>x.id!=="android-featured-hero");'
    'base={schema:1,featured:bf,search:{customizations:mc,groups:mg},details:Object.assign({},base.details||{},rem.details||{})}'
    '}}}catch(_e2){}'
    'window.__agMpCatalog=base;return base})();'
    'return window.__agMpCatalogPromise})'
)

NEW_MP_BLOCK_V3 = (
    'searchMarketplace:async u=>{' + LOADER_INIT_V3 + ';'
    'var _SMR=Ija.file.messages.find(m=>m.name==="SearchMarketplaceResponse")||Ija.file.messages[514],'
    '_q=(u.query??"").trim().toLowerCase(),_gid=u.groupId??"",_pt=u.pageToken??"";'
    'var [_cat,_sr]=await Promise.all([window.__agLoadMarketplaceCatalog().catch(()=>null),_gid==="built-for-android"?Promise.resolve(null):f(v=>v.searchMarketplace(cc(Ija,{query:u.query??"",groupId:_gid,pageToken:_pt}))).catch(()=>null)]);'
    'try{var _allCat=(_cat&&_cat.search&&_cat.search.customizations)||[],_allGrps=(_cat&&_cat.search&&_cat.search.groups)||[],_hasLive=!!(_sr&&_sr.customizations&&_sr.customizations.length>0);'
    'if(_pt&&_hasLive){var _natRaw=_allCat.filter(x=>x._androidNative);if(_natRaw.length>0){var _natMsg=lf(_SMR,{customizations:_natRaw,groups:[]},{ignoreUnknownFields:!0}),_nm=new Map((_natMsg.customizations||[]).map(x=>[x.id,x]));_sr.customizations=_sr.customizations.map(x=>_nm.get(x.id)||x)}return _sr}'
    'var _srcItems=_hasLive?_allCat.filter(x=>x._androidNative):_allCat,_srcGrps=_hasLive?_allGrps.filter(x=>x.id==="built-for-android"||x._androidNative):_allGrps;'
    'if(_gid){var _g=_allGrps.find(x=>x.id===_gid);_srcItems=_g?_srcItems.filter(x=>_g.memberIds&&_g.memberIds.includes(x.id)):[]}'
    'if(_q){_srcItems=_srcItems.filter(x=>(x.displayName||"").toLowerCase().includes(_q)||(x.id||"").toLowerCase().includes(_q)||(x.description||"").toLowerCase().includes(_q))}'
    'var _localMsg=lf(_SMR,{customizations:_srcItems,groups:_q||_gid?[]:_srcGrps},{ignoreUnknownFields:!0});'
    'if(_hasLive){var _fullNatMsg=lf(_SMR,{customizations:_allCat.filter(x=>x._androidNative),groups:[]},{ignoreUnknownFields:!0}),_natById=new Map((_fullNatMsg.customizations||[]).map(x=>[x.id,x])),_seen=new Set((_localMsg.customizations||[]).map(x=>x.id)),_merged=(_localMsg.customizations||[]).slice();'
    'for(var _it of _sr.customizations){var _rep=_natById.get(_it.id)||_it;if(!_seen.has(_rep.id)){_seen.add(_rep.id);_merged.push(_rep)}}'
    'var _gSeen=new Set((_localMsg.groups||[]).map(x=>x.id)),_mGrps=_q||_gid?[]:(_localMsg.groups||[]).slice();'
    'if(!_q&&!_gid&&_sr.groups){for(var _gp of _sr.groups){if(!_gSeen.has(_gp.id)){_gSeen.add(_gp.id);_mGrps.push(_gp)}}}'
    '_localMsg.customizations=_merged;_localMsg.groups=_mGrps;_localMsg.nextPageToken=_sr.nextPageToken||""}'
    'return _localMsg}catch(_e2){return _sr||lf(_SMR,{customizations:[],groups:[]},{ignoreUnknownFields:!0})}},'
    'getMarketplaceCustomization:async u=>{' + LOADER_INIT_V3 + ';'
    'var _GCR=Lja.file.messages.find(m=>m.name==="GetMarketplaceCustomizationResponse")||Lja.file.messages[535],'
    '_cat=await window.__agLoadMarketplaceCatalog().catch(()=>null),_localItem=((_cat&&_cat.search&&_cat.search.customizations)||[]).find(x=>x.id===u.id),_localDet=(_cat&&_cat.details&&_cat.details[u.id])||(_localItem?{customization:_localItem}:null);'
    'if((_localItem&&_localItem._androidNative)||(_localDet&&_localDet.customization&&_localDet.customization._androidNative)){return lf(_GCR,_localDet,{ignoreUnknownFields:!0})}'
    'try{var _gr=await f(v=>v.getMarketplaceCustomization(cc(Lja,{id:u.id,includePreview:u.includePreview??!1})));if(_gr&&_gr.customization)return _gr}catch(_e){}'
    'return lf(_GCR,_localDet||{},{ignoreUnknownFields:!0})},'
    'getMarketplaceBundles:async u=>{var _GBR=Hja.file.messages.find(m=>m.name==="GetMarketplaceBundlesResponse")||Hja.file.messages[512];try{return await f(v=>v.getMarketplaceBundles(cc(Hja,{ids:u.ids??[]})))}catch(_e){return lf(_GBR,{bundles:[],customizations:[]},{ignoreUnknownFields:!0})}},'
    'getMarketplaceFeatured:async()=>{' + LOADER_INIT_V3 + ';'
    'var _GFR=Jja.file.messages.find(m=>m.name==="GetMarketplaceFeaturedResponse")||Jja.file.messages[526];'
    'try{var _fr=await f(u=>u.getMarketplaceFeatured(cc(Jja,{})));if(_fr&&_fr.featured&&_fr.featured.length>0)return _fr}catch(_e){}'
    'try{var _cat=await window.__agLoadMarketplaceCatalog();return lf(_GFR,{featured:((_cat&&_cat.featured)||[]).filter(x=>x.id!=="android-featured-hero"),customizations:(_cat&&_cat.search&&_cat.search.customizations)||[]},{ignoreUnknownFields:!0})}catch(_e2){return lf(_GFR,{featured:[],customizations:[]},{ignoreUnknownFields:!0})}},'
    'getOnboardingPersonas:async()=>f(u=>u.getOnboardingPersonas(cc(Kja,{}))),'
    'installCustomization:async u=>{' + LOADER_INIT_V3 + ';'
    'var _ICR=Mja.file.messages.find(m=>m.name==="InstallCustomizationResponse")||Mja.file.messages[538],'
    '_cat=await window.__agLoadMarketplaceCatalog().catch(()=>null),_c=((_cat&&_cat.search&&_cat.search.customizations)||[]).find(x=>x.id===u.id),_pname=(u.id||"").split("/").pop(),_url=(_c&&_c.source&&_c.source.archive&&_c.source.archive.url)||"",_mp=(_c&&_c.marketplace)||"antigravity-plugins-official",_ver=(_c&&_c.version)||"1.0.0",_isNat=!!(_c&&_c._androidNative)||["agy-video-studio","craft-anything","chrome-devtools-plugin","android-cli-plugin","science"].includes(_pname);'
    'if(_isNat&&window.Android&&window.Android.installMarketplacePlugin){try{var _njr=JSON.parse(window.Android.installMarketplacePlugin(_pname,_url,u.id,_mp,_ver));await new Promise(r=>setTimeout(r,350));return lf(_ICR,_njr,{ignoreUnknownFields:!0})}catch(_ne){}}'
    'try{var _ir=await f(v=>v.installCustomization(cc(Mja,{id:u.id,bundleId:u.bundleId})));if(_ir&&_ir.success){try{window.Android&&window.Android.postProcessInstalledPlugin&&window.Android.postProcessInstalledPlugin(_pname)}catch(_pe){}return _ir}}catch(_e){}'
    'try{if(window.Android&&window.Android.installMarketplacePlugin){var _jr=JSON.parse(window.Android.installMarketplacePlugin(_pname,_url,u.id,_mp,_ver));await new Promise(r=>setTimeout(r,350));return lf(_ICR,_jr,{ignoreUnknownFields:!0})}}catch(_e2){return lf(_ICR,{success:!1,message:String(_e2)},{ignoreUnknownFields:!0})}'
    'return lf(_ICR,{success:!1,message:"Marketplace install unavailable"},{ignoreUnknownFields:!0})},'
)


def apply_application_tab_patch(content):
    content = must_replace(
        content,
        'IIb="General Appearance Shortcuts Notifications Models Customizations Automations Browser Developer Running".split(" ")',
        'IIb="General App Appearance Shortcuts Notifications Models Customizations Automations Browser Developer Running".split(" ")',
        "IIb_enable_App_screen",
    )
    content = must_replace(
        content,
        'DMd={Notifications:X$,["Jetski Chat"]:X$,App:{feature:{visible:!1},applyFirst:[{runtimeGating:{host:[Fi.Electron,Fi.Web.Desktop.RemoteControl,Fi.Web.Mobile.RemoteControl]},feature:{visible:!0}}],\nimportant:[W$]}}',
        'DMd={Notifications:X$,["Jetski Chat"]:X$,App:{feature:{visible:!0}}}',
        "DMd_App_visible",
    )
    content = must_replace(
        content,
        'zNd={App:{feature:{visible:!1}}}',
        'zNd={App:{feature:{visible:!0}}}',
        "zNd_App_visible",
    )
    content = must_replace(
        content,
        'return D.createElement(ZS,{title:"Application",description:`Manage ${c} app settings.`},D.createElement(tNb,{screen:"App",sectionTitle:"General"})',
        'return D.createElement(ZS,{title:"Application",description:`Manage ${c} app settings.`},D.createElement("div",{id:"agy-application-settings-anchor",className:"flex flex-col gap-8 w-full"}),D.createElement(tNb,{screen:"App",sectionTitle:"General"})',
        "vNb_application_anchor",
    )
    content = must_replace(
        content,
        'var $Kb=({title:a="Notifications"})=>{var b=ld().core.notificationService,c=(0,D.useCallback)(async()=>{var d=e,f=e;try{f(await d(b.openPreferences()))}finally{d()}},[b]);',
        'var $Kb=({title:a="Notifications"})=>{var b=ld().core.notificationService,c=(0,D.useCallback)(async()=>{try{if(window.Android&&typeof window.Android.openAppSettings==="function"){window.Android.openAppSettings();return}}catch(_e){}var d=e,f=e;try{f(await d(b.openPreferences()))}finally{d()}},[b]);',
        "app_notifications_android_settings",
    )
    return content


def patch_main(file_path):
    if not os.path.isfile(file_path):
        print(f"[web-patch-2.33.0] file not found: {file_path}", file=sys.stderr)
        return False

    with open(file_path, "r", encoding="utf-8") as f:
        content = f.read()

    if "Android.getTheme" in content and "__agMpSmartV3" in content and "__AgRootEB" in content:
        if "agy-application-settings-anchor" in content:
            print("[web-patch-2.33.0] main.js already patched (v2.33.1 Android UI + Application Tab + Smart Marketplace V3 + RootEB)")
            return True
        content = apply_application_tab_patch(content)
        assert "Desktop Mode" not in content, "Desktop Mode must not be present in Android main.js"
        with open(file_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(content)
        print("[web-patch-2.33.0] Upgraded patched main.js with Application settings tab!")
        return True

    # 1a. g1() system theme resolver via window.Android.getTheme()
    content = must_replace(
        content,
        'function g1(){return typeof window!=="undefined"&&window.matchMedia?window.matchMedia("(prefers-color-scheme: dark)").matches:!1}',
        'function g1(){try{if(typeof window!=="undefined"&&window.Android&&typeof window.Android.getTheme==="function"){var _t=window.Android.getTheme();if(_t==="dark")return!0;if(_t==="light")return!1}if(typeof window!=="undefined"&&window.__AG_RESOLVED_THEME__){if(window.__AG_RESOLVED_THEME__==="dark")return!0;if(window.__AG_RESOLVED_THEME__==="light")return!1}}catch(_e){}return typeof window!=="undefined"&&window.matchMedia?window.matchMedia("(prefers-color-scheme: dark)").matches:!1}',
        "g1_system_theme",
    )

    # 1b. FCd(a) hostTheme=system resolver + unconditional startup call
    content = must_replace(
        content,
        'function FCd(a){if(typeof window!=="undefined"){var b=(new URLSearchParams(window.location.search)).get("hostTheme");b!=="dark"&&b!=="light"||sh.setState(b);',
        'function FCd(a){if(typeof window!=="undefined"){var b=(new URLSearchParams(window.location.search)).get("hostTheme");b==="system"&&(b=typeof window!=="undefined"&&window.Android&&window.Android.getTheme?window.Android.getTheme():(typeof window!=="undefined"?window.__AG_RESOLVED_THEME__:void 0));b!=="dark"&&b!=="light"||sh.setState(b);',
        "FCd_hostTheme",
    )
    content = must_replace(
        content,
        "d&&FCd(f?.router);",
        "FCd(f?.router);",
        "FCd_startup_call",
    )

    # 1c. Theme sync (window.Android.setTheme, __agOnSystemThemeChanged, setThemeByUser)
    content = must_replace(
        content,
        'z=B=>{m.setState(B);B=h1({hostTheme:b?sh.getState():void 0,themeMode:B});var E=u.getState();E=B===2?E.light:E.dark;var G=i1(B);b1(G,E,B,b);l.setState(G);y(G)}',
        'z=B=>{m.setState(B);var _origB=B;B=h1({hostTheme:b?sh.getState():void 0,themeMode:B});var E=u.getState();E=B===2?E.light:E.dark;var G=i1(B);b1(G,E,B,b);l.setState(G);try{var _m=G.uiTheme==="vs-dark"?"dark":"light",_thm=b||_origB!==1?_m:"system";window.__agSelectedTheme=_thm;window.Android&&window.Android.setTheme&&window.Android.setTheme(_thm)}catch(_e){}y(G)}',
        "theme_setTheme",
    )
    content = must_replace(
        content,
        ':window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change",()=>{m.getState()===1&&z(1)});',
        ':(c=()=>{if(c._updating)return;c._updating=!0;try{var _cur=window.Android&&window.Android.getTheme?window.Android.getTheme():window.__AG_RESOLVED_THEME__;if((_cur==="dark"||_cur==="light")&&sh.getState()!==_cur)sh.setState(_cur)}catch(_e){}try{m.getState()===1&&z(1)}catch(_e){}try{typeof window!=="undefined"&&window.dispatchEvent(typeof CustomEvent==="function"?new CustomEvent("__ag_theme_change"):new Event("__ag_theme_change"))}catch(_e){}c._updating=!1},c._updating=!1,window.__agOnSystemThemeChanged=c,sh.onDidChange(()=>{c._updating||c()}),window.matchMedia&&window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change",c));',
        "theme_system_listener",
    )
    content = must_replace(
        content,
        "pushUpdate:B=>{switch(B){case 2:B=2;break;case 3:B=1;break;case 1:B=3;break;default:B=1}vCd(a,B)}",
        'pushUpdate:B=>{var _orig=B;switch(B){case 2:B=2;break;case 3:B=1;break;case 1:B=3;break;default:B=1}vCd(a,B);z(_orig);try{var _um=_orig===2?"light":(_orig===3?"dark":"system");window.Android&&window.Android.setThemeByUser&&window.Android.setThemeByUser(_um)}catch(_e){}}',
        "theme_pushUpdate",
    )

    # 2. AuxPane v2 redesign + Overview [📄] popover button (v8c) on Mobile & Desktop
    content = must_replace(
        content,
        'auxPaneComponent:{feature:{overviewPanelMode:"outputs"}}',
        'auxPaneComponent:{feature:{overviewPanelMode:"full",redesignEnabled:!0}}',
        "auxPane_outputs_to_full",
    )
    content = must_replace(
        content,
        '!b&&D.createElement(D.Fragment,null,\nD.createElement(c9c,{cascadeId:t}),q!==void 0&&D.createElement(UX,{iconName:"dock_to_bottom",onClick:p,"aria-label":"Toggle Side Panel",dataTestId:"mobile-toggle-aux-sidebar",size:"icon-touch",showAttentionDot:n}))',
        '!b&&D.createElement(D.Fragment,null,\nD.createElement(c9c,{cascadeId:t}),D.createElement(v8c,null),q!==void 0&&D.createElement(UX,{iconName:"dock_to_bottom",onClick:p,"aria-label":"Toggle Side Panel",dataTestId:"mobile-toggle-aux-sidebar",size:"icon-touch",showAttentionDot:n}))',
        "mobile_header_v8c",
    )
    content = must_replace(
        content,
        'n=u?D.createElement(Jgd,{content:da,sidePanel:Z,statusBar:V}):D.createElement(Wgd,',
        'n=u?D.createElement(Jgd,{content:W,sidePanel:Z,statusBar:V}):D.createElement(Wgd,',
        "mobile_Jgd_with_overview_host",
    )
    content = must_replace(
        content,
        'function Q6c(a,b){a=b??a;',
        'function Q6c(a,b){a=a??b;',
        "Q6c_keep_open_when_count_positive",
    )
    content = must_replace(
        content,
        '{id:"modifiedFiles",title:"Modified Files",defaultEnabled:!1}',
        '{id:"modifiedFiles",title:"Modified Files",defaultEnabled:!0}',
        "enable_modifiedFiles_by_default",
    )
    content = must_replace(
        content,
        '{type:"fileView",id:"open-file",title:"Open File",icon:D.createElement(Nq,{name:"draft",size:16}),onSelect:({logEvent:a,commandsService:b})=>{a("aux_new_tab_open_file_click");\nb?.executeCommand("openCommandPalette")}}',
        '{type:"fileView",id:"open-file",title:"Open File",icon:D.createElement(Nq,{name:"draft",size:16}),onSelect:({logEvent:a,openSidePanelTab:b,panelScope:c})=>{a("aux_new_tab_open_file_click");\nb({type:"fileView",fileView:{}},void 0,{panelScope:c,keepOpen:!0,source:"new_tab_card"})}}',
        "open_file_card",
    )
    content = must_replace(
        content,
        'fileExplorerTab:{feature:{isEnabled:!1},',
        'fileExplorerTab:{feature:{isEnabled:!0},',
        "fileExplorerTab_main",
    )

    # 3. Enable Plugins/Marketplace V3 & Automations and wire them inside Settings (⚙️)
    content = must_replace(
        content,
        'plugins:{feature:!1,\napplyAll:[{runtimeGating:{customFlag:"enable-plugins"},feature:!0}]}',
        'plugins:{feature:!0,\napplyAll:[{runtimeGating:{customFlag:"enable-plugins"},feature:!0}]}',
        "plugins_feature",
    )
    content = must_replace(
        content,
        'x=oh(Vi(b,"enable-sidecars"),B=>k&&(l||B));',
        'x=new nh(!0);',
        "extensibility_x",
    )
    content = must_replace(
        content,
        'showUISidecars:Vi(b,"enable-ui-sidecars"),showDeclaredPermissions:Vi(b,"jetski-sidecar-declared-permissions"),enableAutomations:rh(!0)',
        'showUISidecars:rh(!0),showDeclaredPermissions:Vi(b,"jetski-sidecar-declared-permissions"),enableAutomations:rh(!0)',
        "extensibility_flags",
    )
    content = must_replace(
        content,
        'deletePlugin:async u=>f(async v=>{var w=cc(wja);w.pluginId=u.pluginId;return v.deletePlugin(w)}),listPluginAuthProviders:async u=>f(v=>v.listPluginAuthProviders(cc(xja,\nu??{}))),',
        'deletePlugin:async u=>{try{var _dr=await f(async v=>{var w=cc(wja);w.pluginId=u.pluginId;return v.deletePlugin(w)});if(_dr&&_dr.success!==!1)return _dr}catch(_e){}try{if(window.Android&&window.Android.uninstallMarketplacePlugin&&window.Android.uninstallMarketplacePlugin(u.pluginId)){await new Promise(r=>setTimeout(r,250));return{success:!0,message:""}}}catch(_e2){}return{success:!1,message:"Failed to delete plugin"}},listPluginAuthProviders:async()=>({providers:[]}),',
        "deletePlugin_and_authProviders_fallback",
    )
    content = must_replace(
        content,
        'searchMarketplace:async u=>f(v=>v.searchMarketplace(cc(Ija,{query:u.query??"",groupId:u.groupId??"",pageToken:u.pageToken??""}))),getMarketplaceCustomization:async u=>f(v=>v.getMarketplaceCustomization(cc(Lja,{id:u.id,includePreview:u.includePreview??!1}))),getMarketplaceBundles:async u=>f(v=>v.getMarketplaceBundles(cc(Hja,{ids:u.ids??[]}))),getMarketplaceFeatured:async()=>f(u=>u.getMarketplaceFeatured(cc(Jja,{}))),getOnboardingPersonas:async()=>\nf(u=>u.getOnboardingPersonas(cc(Kja,{}))),installCustomization:async u=>f(v=>v.installCustomization(cc(Mja,{id:u.id,bundleId:u.bundleId}))),',
        NEW_MP_BLOCK_V3,
        "marketplace_rpcs_v3",
    )
    content = must_replace(
        content,
        'function bW(a,b){return b.find(c=>JRb(c.installedFrom,a))}',
        'function bW(a,b){return b.find(c=>JRb(c.installedFrom,a)||(a?.id&&aW(c)===a.id.split("/").pop()))}',
        "bW_match_by_dir_fallback",
    )
    content = must_replace(
        content,
        '["Jetski Chat",{sections:[]}],["Developer",{sections:[]}]]',
        '["Automations",{sections:[]}],["Jetski Chat",{sections:[]}],["Developer",{sections:[]}]]',
        "AIb_screens",
    )
    content = must_replace(
        content,
        '{screen:"Customizations"},{screen:"Browser"},{screen:"Developer"},{screen:"Running"},{screen:"Jetski Chat"}],IIb="General Appearance Shortcuts Notifications Models Customizations Browser Developer Running".split(" ")',
        '{screen:"Customizations"},{screen:"Automations"},{screen:"Browser"},{screen:"Developer"},{screen:"Running"},{screen:"Jetski Chat"}],IIb="General Appearance Shortcuts Notifications Models Customizations Automations Browser Developer Running".split(" ")',
        "HIb_IIb_screens",
    )
    content = must_replace(
        content,
        '{screen:"Account"},{screen:"Customizations"},{screen:"Notifications"}',
        '{screen:"Account"},{screen:"Customizations"},{screen:"Automations"},{screen:"Notifications"}',
        "mobile_settings_screens",
    )
    content = must_replace(
        content,
        'if(!c)return f;f=new Map(f);f.delete("Customizations");return f',
        'return f',
        "keep_customizations_in_settings",
    )
    content = must_replace(
        content,
        'var $Tb=()=>{var {authorities:a,selectedAuthority:b,setSelectedAuthority:c,selectedGeminiDir:d,selectedConfigDir:f}=eW(),g=vj().get("projectsCustomizations")?.showHeadCustomizationsInMainScreen??!1,h=(0,D.useMemo)(()=>g?["file:///google/src/files/head/depot/"]:[],[g]);return a.length===0?D.createElement(ZS,{title:"Customizations"},D.createElement("div",{className:"flex items-center gap-2 text-muted-foreground"},D.createElement(jv,{size:14,className:"text-muted-foreground"}),"Connecting to language server...")):\nD.createElement(ZTb,{key:b,geminiDir:d,configDir:f,workspaceUris:h,tabs:a.map(k=>({id:k.authority,label:k.displayName})),selectedAuthority:b,onSelectAuthority:c})};',
        'var $Tb=()=>D.createElement(M6b,null,D.createElement(cD,null,D.createElement(q6c,{embedded:!0})));',
        "$Tb_replace_with_marketplace_q6c",
    )
    content = must_replace(
        content,
        'case "Customizations":return D.createElement($Tb,null);',
        'case "Customizations":return D.createElement($Tb,null);case "Automations":return D.createElement(Wjd,{automationPage:!0});',
        "mobile_automations_screen",
    )
    content = must_replace(
        content,
        'D.createElement("div",{style:{display:c==="Customizations"?"block":"none"}},D.createElement($Tb,null))',
        'D.createElement("div",{style:{display:c==="Customizations"?"block":"none"}},D.createElement($Tb,null)),D.createElement("div",{style:{display:c==="Automations"?"block":"none"}},D.createElement(Wjd,{automationPage:!0}))',
        "desktop_settings_automations_screen",
    )
    content = must_replace(
        content,
        'b({to:"/",search:h=>{h={...h};delete h.cascadeId;delete h.focused;',
        'b({to:"/",search:h=>{h={...h};delete h.cascadeId;delete h.focused;delete h.settingsOpen;delete h.settingsScreen;delete h.settingsProjectId;delete h.settingsSection;',
        "uJ_close_settings",
    )

    # Dual-mode l6c / q6c inside Settings modal and on /customizations route
    old_l6c_q6c = (
        'function l6c({tab:a,onSelect:b}){return D.createElement("nav",{"aria-label":"Customizations views",className:"agy-pill inline-flex items-center h-8 rounded-lg border border-border bg-secondary/20 p-0.5 gap-0.5 select-none","data-testid":"plugins-tabs"},k6c.map(({tab:c,to:d,label:f})=>{var g=c===a;return D.createElement(RI,{key:c,to:d,activeOptions:{exact:!0},"aria-current":g?"page":void 0,onClick:()=>{g||b(c)},className:Oi("agy-pill inline-flex items-center justify-center h-full px-2 rounded-md shrink-0 whitespace-nowrap no-underline transition-colors cursor-pointer outline-none focus-visible:ring-1 focus-visible:ring-primary",\n'
        'g?"bg-secondary text-foreground":"text-secondary-foreground hover:text-foreground"),"data-testid":`plugins-tab-${c}`},f)}))};const m6c={source:"remote"};\n'
        'function n6c({tab:a,MarkdownRenderer:b}){var {plugins:c,error:d,dismissError:f,refetch:g}=UUb(),h=vj().get("customizations");h=!!h?.showSkillsAndRules||!!h?.showCustomAgents||!!h?.showMcpServers;var k=z6().trim(),l=k.trim().toLowerCase(),m=(0,D.useMemo)(()=>(c??[]).filter(n=>(a==="browse"||n.isAuthenticated)&&A6(l,n.displayName,n.name,n.description)),[c,a,l]);k=k?`No apps found for "${k}".`:a==="browse"?"No apps available.":"No apps connected yet. Connect one from Marketplace.";return D.createElement("div",\n'
        '{className:"flex flex-col gap-6"},D.createElement(o6c,null),D.createElement(L5,{title:a==="browse"?"Apps":"Connected",count:c?m.length:void 0,isFirst:!0},c?D.createElement(D.Fragment,null,d&&D.createElement(MW,{title:"Error:",message:d,onDismiss:f}),m.length===0?D.createElement(C5c,{message:k}):D.createElement("div",{className:"grid gap-2 grid-cols-1 sm:grid-cols-2"},m.map(n=>{var p=n.displayName||n.name,q=n.accounts.length>0?"Reconnect":"Connect";return D.createElement(y5,{key:n.name,name:p,description:n.description,\n'
        'logo:n.logo,params:{pluginId:n.name},search:m6c,searchQuery:l,badge:a==="browse"&&n.isAuthenticated?D.createElement(w5,{variant:"muted"},"Connected"):void 0,action:!n.isAuthenticated&&n.authorizeUri?D.createElement(Qz,{variant:"secondary",size:"xs","aria-label":`${q} ${p}`,onClick:()=>{Tm(n.authorizeUri)}},q):null})}))):d?D.createElement(B5c,{error:d,onRetry:()=>{g()}}):D.createElement(A5c,{label:"Loading apps..."})),a==="installed"&&h&&D.createElement(y5c,{MarkdownRenderer:b}))}\n'
        'function o6c(){return D.createElement("div",{className:"flex gap-3 items-start px-4 py-3 rounded-xl border border-border"},D.createElement(Nq,{name:"cloud",size:20,className:"shrink-0 mt-0.5 text-muted-foreground"}),D.createElement("div",{className:"flex flex-col gap-1"},D.createElement("p",{className:"font-medium text-foreground m-0"},"Apps connected to your account"),D.createElement("p",{className:"text-sm text-muted-foreground m-0"},"Sign in to an app once and your agent can use it on any device. Open an app to see the account it uses or to disconnect it.")))}\n'
        ';function p6c({searchQuery:a,children:b}){a=F5c(a);return D.createElement(D5c.Provider,{value:a},b)};function q6c({MarkdownRenderer:a}={}){var {q:b}=VI({strict:!1}),c=JI({select:w=>w.location.pathname}),d=vj().get("customizations"),f=d?.showMarketplaceTab??!0,g=!f||c.startsWith("/customizations/installed")?"installed":"browse";c=d?.pluginSources??"local";var h=Z_(),k=c==="remote",[l,m]=(0,D.useState)(()=>b??h.searchQuery),[n,p]=(0,D.useState)(b);n!==b&&(p(b),b!==void 0&&m(b));(0,D.useEffect)(()=>{h.searchQuery=l},[h,l]);var {logTabToggle:q,logSearch:t}=B5(),u=sAc(l,800),v=(0,D.useRef)(l);(0,D.useEffect)(()=>\n'
        '{k||u===v.current||(v.current=u)&&t(g,u)},[u,g,k,t]);return D.createElement("div",{className:"flex-1 flex flex-col overflow-hidden h-full min-h-0 bg-background"},D.createElement("div",{className:"flex-1 overflow-y-auto min-h-0 [scrollbar-gutter:stable]"},D.createElement("div",{className:"sticky top-0 z-20 bg-background"},D.createElement("div",{className:"w-full max-w-4xl mx-auto px-4 sm:px-8 pt-8 pb-4 flex flex-col gap-4"},D.createElement("div",{className:"flex flex-wrap items-center justify-between gap-2 sm:gap-4"},\n'
        'D.createElement("h1",{className:"text-xl font-medium text-foreground select-none sm:px-3 m-0"},"Customizations"),D.createElement("div",{className:"flex items-center gap-4"},f&&D.createElement(l6c,{tab:g,onSelect:q}))),D.createElement(j6c,{searchQuery:l,onSearchQueryChange:m,placeholder:"Search customizations...",inputTestId:"customizations-search"}))),D.createElement("div",{className:"max-w-4xl w-full mx-auto px-4 sm:px-8 py-6"},D.createElement(p6c,{searchQuery:k?"":l},D.createElement(M4c,{value:l},\n'
        'k?D.createElement(n6c,{tab:g,MarkdownRenderer:a}):D.createElement(NI,null))))))}'
    )
    new_l6c_q6c = (
        'function l6c({tab:a,onSelect:b,inRoute:inR=!0}){return D.createElement("nav",{"aria-label":"Customizations views",className:"agy-pill inline-flex items-center h-8 rounded-lg border border-border bg-secondary/20 p-0.5 gap-0.5 select-none","data-testid":"plugins-tabs"},k6c.map(({tab:c,to:d,label:f})=>{var g=c===a,cls=Oi("agy-pill inline-flex items-center justify-center h-full px-2 rounded-md shrink-0 whitespace-nowrap no-underline transition-colors cursor-pointer outline-none focus-visible:ring-1 focus-visible:ring-primary",g?"bg-secondary text-foreground":"text-secondary-foreground hover:text-foreground");return inR?D.createElement(RI,{key:c,to:d,activeOptions:{exact:!0},"aria-current":g?"page":void 0,onClick:()=>{g||b(c)},className:cls,"data-testid":`plugins-tab-${c}`},f):D.createElement("button",{key:c,type:"button","aria-current":g?"page":void 0,onClick:()=>{g||b(c)},className:Oi(cls,"border-none bg-transparent"),"data-testid":`plugins-tab-${c}`},f)}))};const m6c={source:"remote"};\n'
        'function n6c({tab:a,MarkdownRenderer:b}){var {plugins:c,error:d,dismissError:f,refetch:g}=UUb(),h=vj().get("customizations");h=!!h?.showSkillsAndRules||!!h?.showCustomAgents||!!h?.showMcpServers;var k=z6().trim(),l=k.trim().toLowerCase(),m=(0,D.useMemo)(()=>(c??[]).filter(n=>(a==="browse"||n.isAuthenticated)&&A6(l,n.displayName,n.name,n.description)),[c,a,l]);k=k?`No apps found for "${k}".`:a==="browse"?"No apps available.":"No apps connected yet. Connect one from Marketplace.";return D.createElement("div",\n'
        '{className:"flex flex-col gap-6"},D.createElement(o6c,null),D.createElement(L5,{title:a==="browse"?"Apps":"Connected",count:c?m.length:void 0,isFirst:!0},c?D.createElement(D.Fragment,null,d&&D.createElement(MW,{title:"Error:",message:d,onDismiss:f}),m.length===0?D.createElement(C5c,{message:k}):D.createElement("div",{className:"grid gap-2 grid-cols-1 sm:grid-cols-2"},m.map(n=>{var p=n.displayName||n.name,q=n.accounts.length>0?"Reconnect":"Connect";return D.createElement(y5,{key:n.name,name:p,description:n.description,\n'
        'logo:n.logo,params:{pluginId:n.name},search:m6c,searchQuery:l,badge:a==="browse"&&n.isAuthenticated?D.createElement(w5,{variant:"muted"},"Connected"):void 0,action:!n.isAuthenticated&&n.authorizeUri?D.createElement(Qz,{variant:"secondary",size:"xs","aria-label":`${q} ${p}`,onClick:()=>{Tm(n.authorizeUri)}},q):null})}))):d?D.createElement(B5c,{error:d,onRetry:()=>{g()}}):D.createElement(A5c,{label:"Loading apps..."})),a==="installed"&&h&&D.createElement(y5c,{MarkdownRenderer:b}))}\n'
        'function o6c(){return D.createElement("div",{className:"flex gap-3 items-start px-4 py-3 rounded-xl border border-border"},D.createElement(Nq,{name:"cloud",size:20,className:"shrink-0 mt-0.5 text-muted-foreground"}),D.createElement("div",{className:"flex flex-col gap-1"},D.createElement("p",{className:"font-medium text-foreground m-0"},"Apps connected to your account"),D.createElement("p",{className:"text-sm text-muted-foreground m-0"},"Sign in to an app once and your agent can use it on any device. Open an app to see the account it uses or to disconnect it.")))}\n'
        ';function p6c({searchQuery:a,children:b}){a=F5c(a);return D.createElement(D5c.Provider,{value:a},b)};'
        'function q6c({MarkdownRenderer:a,embedded:emb=!1}={}){var {q:b}=VI({strict:!1}),c=JI({select:w=>w.location.pathname}),pMid=D.useContext(LI),inR=!emb&&pMid!==void 0&&c.startsWith("/customizations"),mdR=a||O5,[locTab,setLocTab]=(0,D.useState)("browse"),[det,setDet]=(0,D.useState)(null),d=vj().get("customizations"),f=d?.showMarketplaceTab??!0,g=!f?"installed":inR?(c.startsWith("/customizations/installed")?"installed":"browse"):locTab;c=d?.pluginSources??"local";var h=Z_(),k=c==="remote",[l,m]=(0,D.useState)(()=>b??h.searchQuery),[n,p]=(0,D.useState)(b);n!==b&&(p(b),b!==void 0&&m(b));(0,D.useEffect)(()=>{h.searchQuery=l},[h,l]);'
        '(0,D.useEffect)(()=>{if(!inR){window.__agCustSelect=setDet;window.__agCustTab=setLocTab;return()=>{if(window.__agCustSelect===setDet)window.__agCustSelect=null;if(window.__agCustTab===setLocTab)window.__agCustTab=null}}},[inR]);'
        'var {logTabToggle:q,logSearch:t}=B5(),u=sAc(l,800),v=(0,D.useRef)(l);(0,D.useEffect)(()=>{k||u===v.current||(v.current=u)&&t(g,u)},[u,g,k,t]);'
        'var onTab=tb=>{inR||setLocTab(tb);setDet(null);q(tb)};'
        'if(!inR&&det)return D.createElement("div",{"data-agy-embedded-cust":"1",className:"flex-1 flex flex-col overflow-hidden h-full min-h-0 bg-background"},D.createElement("div",{className:"flex-1 overflow-y-auto min-h-0 [scrollbar-gutter:stable]"},D.createElement("div",{className:"w-full max-w-4xl mx-auto px-4 sm:px-8 py-4"},D.createElement("button",{type:"button",onClick:()=>setDet(null),className:"mb-4 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground bg-transparent border-none cursor-pointer p-0"},D.createElement(Nq,{name:"arrow_back",size:16}),"Customizations"),det.bundleId?D.createElement(F4c,{bundleId:det.bundleId}):D.createElement(HQc,{installDir:det.installDir,catalogId:det.catalogId,onUninstalled:()=>setDet(null),onDisconnected:()=>setDet(null),MarkdownRenderer:mdR}))));'
        'return D.createElement("div",{"data-agy-embedded-cust":inR?void 0:"1",className:"flex-1 flex flex-col overflow-hidden h-full min-h-0 bg-background"},D.createElement("div",{className:"flex-1 overflow-y-auto min-h-0 [scrollbar-gutter:stable]"},D.createElement("div",{className:"sticky top-0 z-20 bg-background"},D.createElement("div",{className:"w-full max-w-4xl mx-auto px-4 sm:px-8 pt-6 pb-4 flex flex-col gap-4"},D.createElement("div",{className:"flex flex-wrap items-center justify-between gap-2 sm:gap-4"},D.createElement("h1",{className:"text-xl font-medium text-foreground select-none sm:px-3 m-0"},"Customizations"),D.createElement("div",{className:"flex items-center gap-4"},f&&D.createElement(l6c,{tab:g,onSelect:onTab,inRoute:inR}))),D.createElement(j6c,{searchQuery:l,onSearchQueryChange:m,placeholder:"Search customizations...",inputTestId:"customizations-search"}))),D.createElement("div",{className:"max-w-4xl w-full mx-auto px-4 sm:px-8 py-6"},D.createElement(p6c,{searchQuery:k?"":g==="browse"?l:""},D.createElement(M4c,{value:l},k?D.createElement(n6c,{tab:g,MarkdownRenderer:mdR}):inR?D.createElement(NI,null):(g==="browse"?D.createElement(e6c,{MarkdownRenderer:mdR}):D.createElement(O5c,{MarkdownRenderer:mdR})))))))}'
    )
    content = must_replace(content, old_l6c_q6c, new_l6c_q6c, "l6c_q6c_dual_mode")

    content = must_replace(
        content,
        'g=Ik(u=>u.newConvoResourceSelector.selectedEnvironmentByProject),h=c.get("currentWorkspace")?.workspaceUri??void 0,k=a||b?.project?.id;a=(0,D.useMemo)(()=>k?d.getProjectStateProvider(k):void 0,[k,d]);a=Lk(a);var l=k?g[k]:void 0',
        'g=Ik(u=>u.newConvoResourceSelector?.selectedEnvironmentByProject),h=c.get("currentWorkspace")?.workspaceUri??void 0,k=a||b?.project?.id;a=(0,D.useMemo)(()=>k?d.getProjectStateProvider(k):void 0,[k,d]);a=Lk(a);var l=k&&g?g[k]:void 0',
        "safe_redux_selectedEnvironmentByProject",
    )

    content = must_replace(
        content,
        'return JI({select:c=>{c=c.matches.find(d=>a.from?a.from===d.routeId:d.id===b);MI(!((a.shouldThrow??!0)&&!c),`Could not find ${a.from?`an active match from "${a.from}"`:"a nearest match!"}`);if(c!==void 0)return a.select?a.select(c):c},structuralSharing:a.structuralSharing})}',
        'return JI({select:c=>{c=c.matches.find(d=>a.from?a.from===d.routeId:d.id===b)||c.matches[c.matches.length-1]||{params:{},_strictParams:{},search:{},_strictSearch:{}};if(c!==void 0)return a.select?a.select(c):c},structuralSharing:a.structuralSharing})}',
        "route_match_fallback_outside_outlet",
    )

    content = must_replace(
        content,
        'D.createElement(RI,{to:d,params:f,search:g,onClick:m,"aria-label":p,"aria-describedby":v,className:"rounded-sm after:absolute after:inset-0 after:rounded-xl focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-primary text-left"}',
        'D.createElement(RI,{to:d,params:f,search:g,onClick:ev=>{m?.(ev);if(typeof window!=="undefined"&&window.__agCustSelect&&ev?.currentTarget?.closest?.(\'[data-agy-embedded-cust]\')){ev.preventDefault();window.__agCustSelect({installDir:d==="/customizations/plugins/$pluginId"?f?.pluginId:void 0,catalogId:g?.id||(f?aC(f):void 0)})}},"aria-label":p,"aria-describedby":v,className:"rounded-sm after:absolute after:inset-0 after:rounded-xl focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-primary text-left"}',
        "y5_card_detail_in_settings",
    )

    content = must_replace(
        content,
        'D.createElement(RI,{to:"/customizations",search:c?{q:b}:void 0,className:d},f)',
        'D.createElement(RI,{to:"/customizations",search:c?{q:b}:void 0,onClick:ev=>{if(typeof window!=="undefined"&&window.__agCustTab&&ev?.currentTarget?.closest?.(\'[data-agy-embedded-cust]\')){ev.preventDefault();window.__agCustTab("browse")}},className:d},f)',
        "browse_in_settings",
    )

    content = must_replace(
        content,
        'var G0=()=>{var {getActiveEditor:a}=(0,D.useContext)(OJ),{logEvent:b}=nC(),c=uJ();return(0,D.useCallback)((d,f)=>{b("AGENT_MANAGER_HOME");c({project:d,clearProject:f?.clearProject});queueMicrotask(()=>{var g=a();g&&document.hasFocus()&&TQ(g)})},[a,b,c])};',
        'var G0=()=>{var {getActiveEditor:a}=(0,D.useContext)(OJ),{logEvent:b}=nC(),c=uJ(),hideS=(0,D.useContext)(HQb)?.triggerHideSettingsModal;return(0,D.useCallback)((d,f)=>{hideS?.();b("AGENT_MANAGER_HOME");c({project:d,clearProject:f?.clearProject});queueMicrotask(()=>{var g=a();g&&document.hasFocus()&&TQ(g)})},[a,b,c,hideS])};',
        "G0_close_settings_on_prompt",
    )

    content = must_replace(
        content,
        'glb=()=>{var a=SI(),b=$kb();return(0,D.useCallback)(c=>{var d=b();a({to:"/sidecars/$sidecarId",params:{sidecarId:c},...(d?{search:d}:{})})},[a,b])}',
        'glb=()=>{var a=SI(),b=$kb(),hideS=(0,D.useContext)(HQb)?.triggerHideSettingsModal;return(0,D.useCallback)(c=>{hideS?.();var d=b();a({to:"/sidecars/$sidecarId",params:{sidecarId:c},...(d?{search:d}:{})})},[a,b,hideS])}',
        "glb_close_settings",
    )

    content = must_replace(
        content,
        'ilb=()=>{var a=SI(),b=$kb();return(0,D.useCallback)(c=>{var d=b();a({to:"/sidecars/triggers/$triggerId",params:{triggerId:c},...(d?{search:d}:{})})},[a,b])}',
        'ilb=()=>{var a=SI(),b=$kb(),hideS=(0,D.useContext)(HQb)?.triggerHideSettingsModal;return(0,D.useCallback)(c=>{hideS?.();var d=b();a({to:"/sidecars/triggers/$triggerId",params:{triggerId:c},...(d?{search:d}:{})})},[a,b,hideS])}',
        "ilb_close_settings",
    )

    content = must_replace(
        content,
        'k=b?jAd(b):"/",l=f(await d(g({initialPath:k,',
        'k=b?jAd(b):"/sdcard",l=f(await d(g({initialPath:k,',
        "default_sdcard_path",
    )

    # 4. Enable Voice Mode, Microphone, Permissions Selector, and touch/menu fixes
    content = must_replace(
        content,
        'inputComponent:{feature:{hideAgentSelectorIfEmpty:!1,hideInSubagentConversation:!1,showPermissionsSelector:!1,disableInlineCompletion:!0,showInternalModesDropdown:!1,\nenableMicrophone:f,showModelUsage:!0,enableFileSystemCategoriesInMentions:!1,enableGoogleWorkspaceMentions:!1,enableVoiceRelay:!1,enablePasteTextAsSnippet:!1,enableHelpCommand:!1',
        'inputComponent:{feature:{hideAgentSelectorIfEmpty:!1,hideInSubagentConversation:!1,showPermissionsSelector:!0,disableInlineCompletion:!0,showInternalModesDropdown:!1,\nenableMicrophone:!0,showModelUsage:!0,enableFileSystemCategoriesInMentions:!1,enableGoogleWorkspaceMentions:!1,enableVoiceRelay:!0,enablePasteTextAsSnippet:!0,enableHelpCommand:!1',
        "inputComponent_full",
    )
    content = must_replace(
        content,
        '{runtimeGating:{host:[Fi.Web.Desktop,Fi.Electron],customFlag:"enable-permissions-selector"},feature:{showPermissionsSelector:!0}}',
        '{runtimeGating:{customFlag:"enable-permissions-selector"},feature:{showPermissionsSelector:!0}}',
        "showPermissionsSelector_gating",
    )
    content = must_replace(
        content,
        'inputComponent:{feature:{disableInlineCompletion:!0,showInternalModesDropdown:!1,enableMicrophone:!1,enableVoiceRelay:!1},applyAll:',
        'inputComponent:{feature:{disableInlineCompletion:!0,showInternalModesDropdown:!1,showPermissionsSelector:!0,enableMicrophone:!0,enableVoiceRelay:!0},applyAll:',
        "inputComponent_short",
    )
    content = must_replace(
        content,
        'remoteConversationCapabilities:{feature:{fork:!1,conversationMentions:!1,sideQuestion:!1,subagentMessaging:!1,revealInFileManager:!1,extensions:!1,connectors:!1,terminal:!1,browserSubagent:!1,localPaths:!1,voice:!1}',
        'remoteConversationCapabilities:{feature:{fork:!1,conversationMentions:!1,sideQuestion:!1,subagentMessaging:!1,revealInFileManager:!1,extensions:!1,connectors:!1,terminal:!0,browserSubagent:!1,localPaths:!1,voice:!0}',
        "remoteConversationCapabilities",
    )
    content = must_replace(
        content,
        'Ua=[...(T9()?["enable-developer-mode"]:[])',
        'Ua=["enable-voice-relay","enable-voice-remote","enable-permissions-selector","enable-paste-text-as-snippet","jetski-autonomous-mode",...(T9()?["enable-developer-mode"]:[])',
        "Ua_customFlags",
    )
    content = must_replace(
        content,
        'let zU={isAvailable:!1,voiceState:"off",voiceConvId:null,muted:!1,liveLines:uU,speed:1};',
        'let zU={isAvailable:!0,voiceState:"off",voiceConvId:null,muted:!1,liveLines:uU,speed:1};',
        "zU_voice_isAvailable",
    )
    content = must_replace(
        content,
        'some(k=>k.kind==="audioinput");b(h)}else b(!1)',
        'some(k=>k.kind==="audioinput");b(!0)}else b(!0)',
        "hasMicrophone_always_true",
    )
    content = must_replace(
        content,
        'enabled:Vi(a,"enable-audio-transcription")}',
        'enabled:rh(!0)}',
        "audioTranscription_enabled",
    )
    content = must_replace(
        content,
        'var I=!n&&c;v=(x||v)&&w;w=a&&!c&&!z?.isOn;var K=b??a;a=q===\n"idle"&&!n&&!E;f=f||l||m||p;l=!!z&&!z.isOn&&!f&&!c&&a;',
        'var I=!n&&c;v=!!w;w=a&&!c&&!z?.isOn;var K=b??a;a=q===\n"idle"&&!E;f=f||l||m||p;l=!!z&&!z.isOn&&!f&&!c&&a;',
        "micAndVoice_idle",
    )
    content = must_replace(
        content,
        "ng=t1c({cascadeId:N,\nplanModel:z?.value,enabled:!pa&&Ve&&(ua??cH).voice&&(!qb||!!N)})",
        "ng=t1c({cascadeId:N,\nplanModel:z?.value||1018,enabled:!pa&&Ve&&(ua??cH).voice&&(!qb||!!N)})",
        "t1c_planModel_fallback",
    )
    content = must_replace(
        content,
        '"shrink-0 whitespace-nowrap @max-[400px]/input-footer:w-7 @max-[400px]/input-footer:justify-center @max-[400px]/input-footer:!p-1.5",I&&"!cursor-not-allowed opacity-50 hover:!bg-transparent")},D.createElement(CZc,{preset:c,className:"@max-[400px]/input-footer:!h-4 @max-[400px]/input-footer:!w-4"}),D.createElement("span",{className:"@max-[400px]/input-footer:hidden"},z),!I&&D.createElement(Nq,{name:"keyboard_arrow_down",size:14,className:"shrink-0 @max-[400px]/input-footer:hidden"})',
        '"shrink-0 whitespace-nowrap",I&&"!cursor-not-allowed opacity-50 hover:!bg-transparent")},D.createElement(CZc,{preset:c,className:""}),D.createElement("span",{className:""},z),!I&&D.createElement(Nq,{name:"keyboard_arrow_down",size:14,className:"shrink-0"})',
        "mobilePermissionsBadge",
    )

    # 5. Touch & Marketplace refresh fixes
    content = must_replace(
        content,
        "Le=mf=>{var $g=Bc.current?.getRootElement();$g&&document.activeElement===$g&&!$g.contains(mf.target)&&mf.preventDefault()}",
        'Le=mf=>{var $g=Bc.current?.getRootElement();$g&&document.activeElement===$g&&!$g.contains(mf.target)&&!(mf.target instanceof Element&&mf.target.closest(\'button,[role="button"],[aria-haspopup],[data-testid="project-selector-trigger"],[data-testid="model-selector-trigger"]\'))&&mf.preventDefault()}',
        "pointerdown_allow_menu_triggers",
    )
    content = must_replace(
        content,
        'return{onPointerDown(x){p.current=x.pointerType},\nonMouseDown(x){var y=p.current,z=x.nativeEvent,A=m.select("open");if(!(x.button!==0||d==="click"||Kw(y,!0)&&g)){var C=w(A,x.currentTarget,E=>E==="click"||E==="mousedown");A=Cw(z);if(Gw(A))v(C,z,A,y);else{var B=x.currentTarget;q.request(()=>{v(C,z,B,y)})}}},onClick(x){if(d!=="mousedown-only"){var y=p.current;if(d==="mousedown"&&y)p.current=void 0;else if(!Kw(y,!0)||!g){var z=m.select("open");z=w(z,x.currentTarget,A=>A==="click"||A==="mousedown"||A==="keydown"||A==="keyup");v(z,x.nativeEvent,x.currentTarget,\ny)}}},onKeyDown(){p.current=void 0}}',
        'return{onPointerDown(x){p.current=x.pointerType;p.md=!1},\nonMouseDown(x){var y=p.current,z=x.nativeEvent,A=m.select("open");if(!(x.button!==0||d==="click"||Kw(y,!0)&&g)){p.md=!0;var C=w(A,x.currentTarget,E=>E==="click"||E==="mousedown");A=Cw(z);if(Gw(A))v(C,z,A,y);else{var B=x.currentTarget;q.request(()=>{v(C,z,B,y)})}}},onClick(x){if(d!=="mousedown-only"){var y=p.current;if(d==="mousedown"&&y&&p.md)p.current=void 0,p.md=!1;else if(p.current=void 0,p.md=!1,!Kw(y,!0)||!g){var z=m.select("open");z=w(z,x.currentTarget,A=>A==="click"||A==="mousedown"||A==="keydown"||A==="keyup");v(z,x.nativeEvent,x.currentTarget,\ny)}}},onKeyDown(){p.current=void 0;p.md=!1}}',
        "BaseUI_click_fallback_when_mousedown_prevented",
    )
    content = must_replace(
        content,
        "async function W_(a){var b=e,c=e;try{a.isStreaming()?c(await b(a.refreshSkills())):c(await b(a.refresh()))}finally{b()}}",
        "async function W_(a){var b=e,c=e;try{c(await b(a.refresh()))}finally{b()}}",
        "W__always_refresh_plugins_and_skills",
    )

    # 6. Global Root ErrorBoundary (__AgRootEB) above SettingsModal & Router to prevent #root unmount gray screens
    old_root_render = (
        '((u=h)=>{n.render(D.createElement(Kra.Provider,{value:f},D.createElement(bld,{...q,featureManagerV2:u,routingConfig:m,updateService:g.updateService,managerSyncedState:g.managerSyncedState,fileSystemProviders:g.fileSystemProviders,\n'
        'MermaidDiagramComponent:iOd,WebEffects:g.WebEffects,diagnosticsService:g.diagnosticsService,settingsSyncedState:g.settingsSyncedState})))})();'
    )
    new_root_render = (
        'var __AgRootEB=class extends D.Component{constructor(pr){super(pr);this.state={err:null,retries:0}}static getDerivedStateFromError(er){return{err:er||Error("UI render error")}}componentDidCatch(er,inf){try{console.error("[AGY RootErrorBoundary]",er,inf&&inf.componentStack)}catch(_e){}if(this.state.retries<1){var _s=this;setTimeout(function(){try{if(window.history&&window.location&&window.location.search.indexOf("settingsOpen")!==-1){var _u=new URL(window.location.href);_u.searchParams.delete("settingsOpen");_u.searchParams.delete("settingsScreen");window.history.replaceState(window.history.state,"",_u.pathname+_u.search+_u.hash)}}catch(_e2){}_s.setState(function(st){return{err:null,retries:st.retries+1}})},60)}}render(){if(!this.state.err)return this.props.children;if(this.state.retries<1)return null;var _msg=String(this.state.err&&(this.state.err.message||this.state.err)||"Unexpected UI error");return D.createElement("div",{"data-testid":"agy-root-error-fallback",style:{display:"flex",flexDirection:"column",alignItems:"center",justifyContent:"center",height:"100vh",width:"100vw",padding:"24px",boxSizing:"border-box",background:"var(--background,#101010)",color:"var(--foreground,#f4f4f5)",fontFamily:"system-ui,-apple-system,sans-serif",textAlign:"center",gap:"12px"}},D.createElement("div",{style:{fontSize:"16px",fontWeight:600}},"Interface recovered from a view error"),D.createElement("div",{style:{fontSize:"12px",opacity:.72,maxWidth:"440px",wordBreak:"break-word"}},_msg),D.createElement("div",{style:{display:"flex",gap:"10px",marginTop:"6px"}},D.createElement("button",{type:"button",onClick:()=>this.setState({err:null}),style:{padding:"8px 14px",borderRadius:"8px",border:"1px solid rgba(128,128,128,0.35)",background:"transparent",color:"inherit",fontSize:"13px",cursor:"pointer"}},"Retry View"),D.createElement("button",{type:"button",onClick:()=>{try{window.location.reload()}catch(_e){}},style:{padding:"8px 14px",borderRadius:"8px",border:"none",background:"#3b82f6",color:"#fff",fontSize:"13px",fontWeight:500,cursor:"pointer"}},"Reload Interface")))}};'
        '((u=h)=>{n.render(D.createElement(__AgRootEB,null,D.createElement(Kra.Provider,{value:f},D.createElement(bld,{...q,featureManagerV2:u,routingConfig:m,updateService:g.updateService,managerSyncedState:g.managerSyncedState,fileSystemProviders:g.fileSystemProviders,\n'
        'MermaidDiagramComponent:iOd,WebEffects:g.WebEffects,diagnosticsService:g.diagnosticsService,settingsSyncedState:g.settingsSyncedState}))))})();'
    )
    content = must_replace(content, old_root_render, new_root_render, "root_error_boundary")
    content = apply_application_tab_patch(content)

    assert "Desktop Mode" not in content, "Desktop Mode must not be present in Android main.js"

    with open(file_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(content)

    print("[web-patch-2.33.0] Successfully patched Android main.js (v2.33.0 + Smart Marketplace V3)!")
    return True


if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else "web/main.js"
    if not patch_main(path):
        sys.exit(1)
