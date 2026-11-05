import{a as $}from"./chunk-D2NST5LP.js";import{D as z,N as L,R as U,b as T,k as F,m as N}from"./chunk-CTZWC4FF.js";import{p as f,q as m}from"./chunk-R44X3CHN.js";import{$ as a,Gb as l,Hb as d,Ib as j,Kc as B,Oc as c,Qb as x,Tb as P,Ua as A,Ub as u,W as s,X as S,Z as v,bb as E,cc as p,e as g,g as _,ga as D,hb as I,ib as H,jb as O,ma as y,ra as w,va as C,wb as h,xb as k,ya as M,yb as R,z as b}from"./chunk-TMT6QMOP.js";var Z=["mat-sort-header",""],G=["*",[["","matSortHeaderIcon",""]]],J=["*","[matSortHeaderIcon]"];function Q(r,i){r&1&&(D(),l(0,"svg",3),j(1,"path",4),d())}function X(r,i){r&1&&(l(0,"div",2),u(1,1,null,Q,2,0),d())}var V=new v("MAT_SORT_DEFAULT_OPTIONS"),tt=(()=>{class r{_defaultOptions;_initializedStream=new _(1);sortables=new Map;_stateChanges=new g;active;start="asc";get direction(){return this._direction}set direction(t){this._direction=t}_direction="";disableClear;disabled=!1;sortChange=new y;initialized=this._initializedStream;constructor(t){this._defaultOptions=t}register(t){this.sortables.set(t.id,t)}deregister(t){this.sortables.delete(t.id)}sort(t){this.active!=t.id?(this.active=t.id,this.direction=t.start?t.start:this.start):this.direction=this.getNextSortDirection(t),this.sortChange.emit({active:this.active,direction:this.direction})}getNextSortDirection(t){if(!t)return"";let n=t?.disableClear??this.disableClear??!!this._defaultOptions?.disableClear,e=et(t.start||this.start,n),o=e.indexOf(this.direction)+1;return o>=e.length&&(o=0),e[o]}ngOnInit(){this._initializedStream.next()}ngOnChanges(){this._stateChanges.next()}ngOnDestroy(){this._stateChanges.complete(),this._initializedStream.complete()}static \u0275fac=function(n){return new(n||r)(E(V,8))};static \u0275dir=O({type:r,selectors:[["","matSort",""]],hostAttrs:[1,"mat-sort"],inputs:{active:[0,"matSortActive","active"],start:[0,"matSortStart","start"],direction:[0,"matSortDirection","direction"],disableClear:[2,"matSortDisableClear","disableClear",c],disabled:[2,"matSortDisabled","disabled",c]},outputs:{sortChange:"matSortChange"},exportAs:["matSort"],features:[C]})}return r})();function et(r,i){let t=["asc","desc"];return r=="desc"&&t.reverse(),i||t.push(""),t}var yt=(()=>{class r{_sort=a(tt,{optional:!0});_columnDef=a($,{optional:!0});_changeDetectorRef=a(B);_focusMonitor=a(F);_elementRef=a(M);_ariaDescriber=a(z,{optional:!0});_renderChanges;_animationsDisabled=L();_recentlyCleared=w(null);_sortButton;id;arrowPosition="after";start;disabled=!1;get sortActionDescription(){return this._sortActionDescription}set sortActionDescription(t){this._updateSortActionDescription(t)}_sortActionDescription="Sort";disableClear;constructor(){a(N).load(U);let t=a(V,{optional:!0});this._sort,t?.arrowPosition&&(this.arrowPosition=t?.arrowPosition)}ngOnInit(){!this.id&&this._columnDef&&(this.id=this._columnDef.name),this._sort.register(this),this._renderChanges=b(this._sort._stateChanges,this._sort.sortChange).subscribe(()=>this._changeDetectorRef.markForCheck()),this._sortButton=this._elementRef.nativeElement.querySelector(".mat-sort-header-container"),this._updateSortActionDescription(this._sortActionDescription)}ngAfterViewInit(){this._focusMonitor.monitor(this._elementRef,!0).subscribe(()=>{Promise.resolve().then(()=>this._recentlyCleared.set(null))})}ngOnDestroy(){this._focusMonitor.stopMonitoring(this._elementRef),this._sort.deregister(this),this._renderChanges?.unsubscribe(),this._sortButton&&this._ariaDescriber?.removeDescription(this._sortButton,this._sortActionDescription)}_toggleOnInteraction(){if(!this._isDisabled()){let t=this._isSorted(),n=this._sort.direction;this._sort.sort(this),this._recentlyCleared.set(t&&!this._isSorted()?n:null)}}_handleKeydown(t){(t.keyCode===32||t.keyCode===13)&&(t.preventDefault(),this._toggleOnInteraction())}_isSorted(){return this._sort.active==this.id&&(this._sort.direction==="asc"||this._sort.direction==="desc")}_isDisabled(){return this._sort.disabled||this.disabled}_getAriaSortAttribute(){return this._isSorted()?this._sort.direction=="asc"?"ascending":"descending":"none"}_renderArrow(){return!this._isDisabled()||this._isSorted()}_updateSortActionDescription(t){this._sortButton&&(this._ariaDescriber?.removeDescription(this._sortButton,this._sortActionDescription),this._ariaDescriber?.describe(this._sortButton,t)),this._sortActionDescription=t}static \u0275fac=function(n){return new(n||r)};static \u0275cmp=I({type:r,selectors:[["","mat-sort-header",""]],hostAttrs:[1,"mat-sort-header"],hostVars:3,hostBindings:function(n,e){n&1&&x("click",function(){return e._toggleOnInteraction()})("keydown",function(W){return e._handleKeydown(W)})("mouseleave",function(){return e._recentlyCleared.set(null)}),n&2&&(h("aria-sort",e._getAriaSortAttribute()),p("mat-sort-header-disabled",e._isDisabled()))},inputs:{id:[0,"mat-sort-header","id"],arrowPosition:"arrowPosition",start:"start",disabled:[2,"disabled","disabled",c],sortActionDescription:"sortActionDescription",disableClear:[2,"disableClear","disableClear",c]},exportAs:["matSortHeader"],attrs:Z,ngContentSelectors:J,decls:4,vars:17,consts:[[1,"mat-sort-header-container","mat-focus-indicator"],[1,"mat-sort-header-content"],[1,"mat-sort-header-arrow"],["viewBox","0 -960 960 960","focusable","false","aria-hidden","true"],["d","M440-240v-368L296-464l-56-56 240-240 240 240-56 56-144-144v368h-80Z"]],template:function(n,e){n&1&&(P(G),l(0,"div",0)(1,"div",1),u(2),d(),k(3,X,3,0,"div",2),d()),n&2&&(p("mat-sort-header-sorted",e._isSorted())("mat-sort-header-position-before",e.arrowPosition==="before")("mat-sort-header-descending",e._sort.direction==="desc")("mat-sort-header-ascending",e._sort.direction==="asc")("mat-sort-header-recently-cleared-ascending",e._recentlyCleared()==="asc")("mat-sort-header-recently-cleared-descending",e._recentlyCleared()==="desc")("mat-sort-header-animations-disabled",e._animationsDisabled),h("tabindex",e._isDisabled()?null:0)("role",e._isDisabled()?null:"button"),A(3),R(e._renderArrow()?3:-1))},styles:[`.mat-sort-header {
  cursor: pointer;
}

.mat-sort-header-disabled {
  cursor: default;
}

.mat-sort-header-container {
  display: flex;
  align-items: center;
  letter-spacing: normal;
  outline: 0;
}
[mat-sort-header].cdk-keyboard-focused .mat-sort-header-container, [mat-sort-header].cdk-program-focused .mat-sort-header-container {
  border-bottom: solid 1px currentColor;
}
.mat-sort-header-container::before {
  margin: calc(calc(var(--mat-focus-indicator-border-width, 3px) + 2px) * -1);
}

.mat-sort-header-content {
  display: flex;
  align-items: center;
}

.mat-sort-header-position-before {
  flex-direction: row-reverse;
}

@keyframes _mat-sort-header-recently-cleared-ascending {
  from {
    transform: translateY(0);
    opacity: 1;
  }
  to {
    transform: translateY(-25%);
    opacity: 0;
  }
}
@keyframes _mat-sort-header-recently-cleared-descending {
  from {
    transform: translateY(0) rotate(180deg);
    opacity: 1;
  }
  to {
    transform: translateY(25%) rotate(180deg);
    opacity: 0;
  }
}
.mat-sort-header-arrow {
  height: 12px;
  width: 12px;
  position: relative;
  transition: transform 225ms cubic-bezier(0.4, 0, 0.2, 1), opacity 225ms cubic-bezier(0.4, 0, 0.2, 1);
  opacity: 0;
  overflow: visible;
  color: var(--mat-sort-arrow-color, var(--mat-sys-on-surface));
}
.mat-sort-header.cdk-keyboard-focused .mat-sort-header-arrow, .mat-sort-header.cdk-program-focused .mat-sort-header-arrow, .mat-sort-header:hover .mat-sort-header-arrow {
  opacity: 0.54;
}
.mat-sort-header .mat-sort-header-sorted .mat-sort-header-arrow {
  opacity: 1;
}
.mat-sort-header-descending .mat-sort-header-arrow {
  transform: rotate(180deg);
}
.mat-sort-header-recently-cleared-ascending .mat-sort-header-arrow {
  transform: translateY(-25%);
}
.mat-sort-header-recently-cleared-ascending .mat-sort-header-arrow {
  transition: none;
  animation: _mat-sort-header-recently-cleared-ascending 225ms cubic-bezier(0.4, 0, 0.2, 1) forwards;
}
.mat-sort-header-recently-cleared-descending .mat-sort-header-arrow {
  transition: none;
  animation: _mat-sort-header-recently-cleared-descending 225ms cubic-bezier(0.4, 0, 0.2, 1) forwards;
}
.mat-sort-header-animations-disabled .mat-sort-header-arrow {
  transition-duration: 0ms;
  animation-duration: 0ms;
}
.mat-sort-header-arrow > svg, .mat-sort-header-arrow [matSortHeaderIcon] {
  width: 24px;
  height: 24px;
  fill: currentColor;
  position: absolute;
  top: 50%;
  left: 50%;
  margin: -12px 0 0 -12px;
  transform: translateZ(0);
}
.mat-sort-header-arrow, [dir=rtl] .mat-sort-header-position-before .mat-sort-header-arrow {
  margin: 0 0 0 6px;
}
.mat-sort-header-position-before .mat-sort-header-arrow, [dir=rtl] .mat-sort-header-arrow {
  margin: 0 6px 0 0;
}
`],encapsulation:2,changeDetection:0})}return r})(),wt=(()=>{class r{static \u0275fac=function(n){return new(n||r)};static \u0275mod=H({type:r});static \u0275inj=S({imports:[T]})}return r})();var q=class r{http=a(m);getRoles(){return this.http.get("/api/intra/admin/roles")}createRole(i){return this.http.post("/api/intra/admin/roles",i)}updateRole(i,t){return this.http.put(`/api/intra/admin/roles/${i}`,t)}deleteRole(i){return this.http.delete(`/api/intra/admin/roles/${i}`)}static \u0275fac=function(t){return new(t||r)};static \u0275prov=s({token:r,factory:r.\u0275fac,providedIn:"root"})};var K=class r{http=a(m);getUsers(i,t,n){let e=new f;return i&&(e=e.set("q",i)),t&&(e=e.set("type",t)),n!=null&&(e=e.set("active",String(n))),this.http.get("/api/intra/admin/users",{params:e})}toggleActive(i){return this.http.patch(`/api/intra/admin/users/${i}/toggle-active`,{})}setRoles(i,t){return this.http.put(`/api/intra/admin/users/${i}/roles`,{roleNames:t})}getAuditLogs(i,t,n,e=200){let o=new f().set("limit",String(e));return i&&(o=o.set("type",i)),t&&(o=o.set("email",t)),n&&(o=o.set("ip",n)),this.http.get("/api/intra/admin/audit-logs",{params:o})}static \u0275fac=function(t){return new(t||r)};static \u0275prov=s({token:r,factory:r.\u0275fac,providedIn:"root"})};export{tt as a,yt as b,wt as c,q as d,K as e};
